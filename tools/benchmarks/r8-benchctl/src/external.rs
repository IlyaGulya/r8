// Copyright (c) 2026, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

use std::collections::BTreeSet;
use std::fs::{self, File};
use std::io::Write;
use std::path::{Path, PathBuf};
use std::process::{Command, Output};

use anyhow::{Context, Result, bail, ensure};
use chrono::{SecondsFormat, Utc};
use serde::Serialize;
use tempfile::{NamedTempFile, tempdir};
use walkdir::WalkDir;

use crate::cell::verify_manifest_files;
use crate::{
    EvidenceFile, ExperimentPlan, InputArtifactManifest, R8ArtifactManifest, ensure_file_hash,
    parse_evidence_manifest, sha256_file, write_json,
};

#[derive(Clone, Debug, Serialize)]
pub struct PlannedCommand {
    pub program: String,
    pub arguments: Vec<String>,
}

#[derive(Clone, Debug, Serialize)]
pub struct SubmitPlan {
    pub plan_uri: String,
    pub plan_sha256: String,
    pub commands: Vec<PlannedCommand>,
}

pub struct SubmitRequest<'a> {
    pub plan: &'a ExperimentPlan,
    pub plan_path: &'a Path,
    pub repository: &'a str,
    pub workflow: &'a str,
    pub workflow_ref: &'a str,
    pub tooling_ref: &'a str,
    pub gcloud: &'a Path,
    pub gh: &'a Path,
    pub dry_run: bool,
}

pub fn submit(request: SubmitRequest<'_>) -> Result<SubmitPlan> {
    request.plan.validate()?;
    validate_repository(request.repository)?;
    validate_safe_name("workflow", request.workflow, 128)?;
    validate_git_ref(request.workflow_ref)?;
    validate_commit(request.tooling_ref)?;
    ensure!(request.plan_path.is_file(), "plan file does not exist");

    let plan_uri = request.plan.plan_uri()?;
    let plan_sha256 = request.plan.fingerprint()?;
    let upload = PlannedCommand {
        program: request.gcloud.display().to_string(),
        arguments: vec![
            "storage".to_string(),
            "cp".to_string(),
            "--no-clobber".to_string(),
            "<canonical-plan>".to_string(),
            plan_uri.clone(),
        ],
    };
    let dispatch = PlannedCommand {
        program: request.gh.display().to_string(),
        arguments: vec![
            "workflow".to_string(),
            "run".to_string(),
            request.workflow.to_string(),
            "--repo".to_string(),
            request.repository.to_string(),
            "--ref".to_string(),
            request.workflow_ref.to_string(),
            "--field".to_string(),
            format!("plan_uri={plan_uri}"),
            "--field".to_string(),
            format!("plan_sha256={plan_sha256}"),
            "--field".to_string(),
            format!("tooling_ref={}", request.tooling_ref),
        ],
    };
    let result = SubmitPlan {
        plan_uri,
        plan_sha256,
        commands: vec![upload, dispatch],
    };
    if request.dry_run {
        return Ok(result);
    }

    let canonical = toml::to_string_pretty(request.plan).context("failed to serialize plan")?;
    let mut temporary = NamedTempFile::new().context("failed to create canonical plan file")?;
    use std::io::Write;
    temporary.write_all(canonical.as_bytes())?;
    temporary.flush()?;
    let reparsed = ExperimentPlan::from_toml_file(temporary.path())?;
    ensure!(
        reparsed.fingerprint()? == result.plan_sha256,
        "canonical plan fingerprint changed"
    );
    run_checked(
        request.gcloud,
        &[
            "storage",
            "cp",
            "--no-clobber",
            temporary.path().to_string_lossy().as_ref(),
            &result.plan_uri,
        ],
    )?;
    run_checked(
        request.gh,
        &result.commands[1]
            .arguments
            .iter()
            .map(String::as_str)
            .collect::<Vec<_>>(),
    )?;
    Ok(result)
}

pub struct PublishArtifactRequest<'a> {
    pub plan: &'a ExperimentPlan,
    pub artifact_id: &'a str,
    pub jar: &'a Path,
    pub gcloud: &'a Path,
    pub dry_run: bool,
}

pub fn publish_artifact(request: PublishArtifactRequest<'_>) -> Result<Vec<PlannedCommand>> {
    let artifact = request
        .plan
        .artifacts
        .get(request.artifact_id)
        .with_context(|| format!("unknown artifact {}", request.artifact_id))?;
    ensure_file_hash(request.jar, &artifact.jar_sha256)?;
    let jar_uri = request.plan.artifact_uri(request.artifact_id)?;
    let manifest_uri = request.plan.artifact_manifest_uri(request.artifact_id)?;
    let manifest = R8ArtifactManifest {
        schema_version: crate::PLAN_SCHEMA_VERSION,
        artifact_id: request.artifact_id.to_string(),
        repository: artifact.repository.clone(),
        commit: artifact.commit.clone(),
        jar_sha256: artifact.jar_sha256.clone(),
        jar_size_bytes: request.jar.metadata()?.len(),
        created_utc: now_utc(),
    };
    publish_with_manifest(
        request.gcloud,
        request.jar,
        &jar_uri,
        &manifest,
        &manifest_uri,
        request.dry_run,
    )
}

pub struct PublishInputRequest<'a> {
    pub plan: &'a ExperimentPlan,
    pub archive: &'a Path,
    pub expected_outputs: &'a Path,
    pub gcloud: &'a Path,
    pub dry_run: bool,
}

pub fn publish_input(request: PublishInputRequest<'_>) -> Result<Vec<PlannedCommand>> {
    ensure_file_hash(request.archive, &request.plan.input.archive_sha256)?;
    ensure_file_hash(
        request.expected_outputs,
        &request.plan.input.expected_outputs_sha256,
    )?;
    let manifest = InputArtifactManifest {
        schema_version: crate::PLAN_SCHEMA_VERSION,
        archive_sha256: request.plan.input.archive_sha256.clone(),
        archive_size_bytes: request.archive.metadata()?.len(),
        expected_outputs_sha256: request.plan.input.expected_outputs_sha256.clone(),
        expected_outputs_size_bytes: request.expected_outputs.metadata()?.len(),
        created_utc: now_utc(),
    };
    let archive = PlannedCommand {
        program: request.gcloud.display().to_string(),
        arguments: vec![
            "storage".to_string(),
            "cp".to_string(),
            "--no-clobber".to_string(),
            request.archive.display().to_string(),
            request.plan.input_uri(),
        ],
    };
    let expected = PlannedCommand {
        program: request.gcloud.display().to_string(),
        arguments: vec![
            "storage".to_string(),
            "cp".to_string(),
            "--no-clobber".to_string(),
            request.expected_outputs.display().to_string(),
            request.plan.expected_outputs_uri(),
        ],
    };
    let manifest_command = PlannedCommand {
        program: request.gcloud.display().to_string(),
        arguments: vec![
            "storage".to_string(),
            "cp".to_string(),
            "--no-clobber".to_string(),
            "<generated-manifest>".to_string(),
            request.plan.input_manifest_uri(),
        ],
    };
    if !request.dry_run {
        run_planned(&archive)?;
        run_planned(&expected)?;
        let temporary = NamedTempFile::new().context("failed to create input manifest")?;
        write_json(temporary.path(), &manifest)?;
        run_checked(
            request.gcloud,
            &[
                "storage",
                "cp",
                "--no-clobber",
                temporary.path().to_string_lossy().as_ref(),
                &request.plan.input_manifest_uri(),
            ],
        )?;
    }
    Ok(vec![archive, expected, manifest_command])
}

fn publish_with_manifest(
    gcloud: &Path,
    file: &Path,
    file_uri: &str,
    manifest: &impl Serialize,
    manifest_uri: &str,
    dry_run: bool,
) -> Result<Vec<PlannedCommand>> {
    let upload = PlannedCommand {
        program: gcloud.display().to_string(),
        arguments: vec![
            "storage".to_string(),
            "cp".to_string(),
            "--no-clobber".to_string(),
            file.display().to_string(),
            file_uri.to_string(),
        ],
    };
    let manifest_command = PlannedCommand {
        program: gcloud.display().to_string(),
        arguments: vec![
            "storage".to_string(),
            "cp".to_string(),
            "--no-clobber".to_string(),
            "<generated-manifest>".to_string(),
            manifest_uri.to_string(),
        ],
    };
    if !dry_run {
        run_planned(&upload)?;
        let temporary = NamedTempFile::new().context("failed to create artifact manifest")?;
        write_json(temporary.path(), manifest)?;
        run_checked(
            gcloud,
            &[
                "storage",
                "cp",
                "--no-clobber",
                temporary.path().to_string_lossy().as_ref(),
                manifest_uri,
            ],
        )?;
    }
    Ok(vec![upload, manifest_command])
}

pub struct CollectRequest<'a> {
    pub plan: &'a ExperimentPlan,
    pub output_dir: &'a Path,
    pub gcloud: &'a Path,
}

pub fn collect_manifests(request: CollectRequest<'_>) -> Result<usize> {
    let expanded = request.plan.expand()?;
    fs::create_dir_all(request.output_dir)?;
    let first = expanded
        .cells
        .first()
        .context("expanded plan has no cells")?;
    let marker = "/cells/";
    let index = first
        .result_uri
        .find(marker)
        .context("cell result URI has no cells segment")?;
    let cells_root = &first.result_uri[..index + marker.len() - 1];
    let listing = run_checked(
        request.gcloud,
        &["storage", "ls", "--recursive", cells_root],
    )?;
    let listed: BTreeSet<_> = String::from_utf8_lossy(&listing.stdout)
        .lines()
        .map(str::trim)
        .filter(|line| line.ends_with("/result.json"))
        .map(str::to_string)
        .collect();
    let mut collected = 0;
    for cell in &expanded.cells {
        let uri = format!("{}/result.json", cell.result_uri);
        if !listed.contains(&uri) {
            continue;
        }
        let destination_dir = request.output_dir.join(&cell.id);
        fs::create_dir_all(&destination_dir)?;
        let temporary = destination_dir.join("result.json.tmp");
        run_checked(
            request.gcloud,
            &["storage", "cp", &uri, temporary.to_string_lossy().as_ref()],
        )?;
        fs::rename(&temporary, destination_dir.join("result.json"))?;
        collected += 1;
    }
    Ok(collected)
}

pub struct PrepareCellRequest<'a> {
    pub plan: &'a ExperimentPlan,
    pub cell_id: &'a str,
    pub output_dir: &'a Path,
    pub gcloud: &'a Path,
    pub tar: &'a Path,
}

#[derive(Clone, Debug, Serialize)]
pub struct PreparedCell {
    pub control_jar: PathBufString,
    pub candidate_jar: PathBufString,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub input_archive: Option<PathBufString>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub input_dir: Option<PathBufString>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub expected_outputs: Option<PathBufString>,
}

#[derive(Clone, Debug, Serialize)]
pub struct PathBufString(String);

impl From<PathBuf> for PathBufString {
    fn from(value: PathBuf) -> Self {
        Self(value.display().to_string())
    }
}

pub fn prepare_cell(request: PrepareCellRequest<'_>) -> Result<PreparedCell> {
    let expanded = request.plan.expand()?;
    let cell = expanded
        .cells
        .iter()
        .find(|cell| cell.id == request.cell_id)
        .with_context(|| format!("plan has no cell {}", request.cell_id))?;
    let control = request
        .plan
        .artifacts
        .get(&cell.control_artifact)
        .context("missing control artifact after plan validation")?;
    let candidate = request
        .plan
        .artifacts
        .get(&cell.candidate_artifact)
        .context("missing candidate artifact after plan validation")?;
    ensure!(
        !request.output_dir.exists(),
        "prepared-cell output directory already exists"
    );
    fs::create_dir_all(request.output_dir)?;
    let control_jar = request.output_dir.join("control.jar");
    let candidate_jar = request.output_dir.join("candidate.jar");
    download(
        request.gcloud,
        &request.plan.artifact_uri(&cell.control_artifact)?,
        &control_jar,
    )?;
    download(
        request.gcloud,
        &request.plan.artifact_uri(&cell.candidate_artifact)?,
        &candidate_jar,
    )?;
    ensure_file_hash(&control_jar, &control.jar_sha256)?;
    ensure_file_hash(&candidate_jar, &candidate.jar_sha256)?;
    let (input_archive, input_dir, expected_outputs) = if cell.kind == crate::CellKind::GradleTiming
    {
        (None, None, None)
    } else {
        let input_archive = request.output_dir.join("input.tar.zst");
        let expected_outputs = request.output_dir.join("expected-outputs.tsv");
        download(request.gcloud, &request.plan.input_uri(), &input_archive)?;
        download(
            request.gcloud,
            &request.plan.expected_outputs_uri(),
            &expected_outputs,
        )?;
        ensure_file_hash(&input_archive, &request.plan.input.archive_sha256)?;
        ensure_file_hash(
            &expected_outputs,
            &request.plan.input.expected_outputs_sha256,
        )?;
        let input_dir = request.output_dir.join("input");
        fs::create_dir(&input_dir)?;
        extract_input_archive(&input_archive, &input_dir)?;
        (
            Some(input_archive.into()),
            Some(input_dir.into()),
            Some(expected_outputs.into()),
        )
    };
    Ok(PreparedCell {
        control_jar: control_jar.into(),
        candidate_jar: candidate_jar.into(),
        input_archive,
        input_dir,
        expected_outputs,
    })
}

fn extract_input_archive(archive_path: &Path, output_dir: &Path) -> Result<()> {
    let file = File::open(archive_path)
        .with_context(|| format!("failed to open input archive {}", archive_path.display()))?;
    let decoder = zstd::Decoder::new(file).context("failed to open zstd input archive")?;
    let mut archive = tar::Archive::new(decoder);
    let mut extracted = BTreeSet::new();
    for entry in archive.entries()? {
        let mut entry = entry?;
        let relative = entry
            .path()?
            .to_str()
            .context("input archive path is not UTF-8")?
            .trim_end_matches('/')
            .to_string();
        ensure!(
            is_safe_relative_path(&relative),
            "unsafe input archive entry {relative}"
        );
        ensure!(
            extracted.insert(relative.clone()),
            "duplicate input archive entry {relative}"
        );
        let destination = output_dir.join(&relative);
        let entry_type = entry.header().entry_type();
        if entry_type.is_dir() {
            fs::create_dir_all(&destination)?;
            continue;
        }
        ensure!(
            entry_type.is_file(),
            "unsupported input archive entry type for {relative}"
        );
        if let Some(parent) = destination.parent() {
            fs::create_dir_all(parent)?;
        }
        let mut output = File::create(&destination)
            .with_context(|| format!("failed to create input file {relative}"))?;
        std::io::copy(&mut entry, &mut output)?;
        output.flush()?;
    }
    ensure!(!extracted.is_empty(), "input archive is empty");
    Ok(())
}

fn download(gcloud: &Path, uri: &str, destination: &Path) -> Result<()> {
    run_checked(
        gcloud,
        &["storage", "cp", uri, destination.to_string_lossy().as_ref()],
    )?;
    ensure!(
        destination.is_file(),
        "download did not create {}",
        destination.display()
    );
    Ok(())
}

pub struct UploadEvidenceRequest<'a> {
    pub plan: &'a ExperimentPlan,
    pub cell_id: &'a str,
    pub result_dir: &'a Path,
    pub gcloud: &'a Path,
    pub dry_run: bool,
}

#[derive(Clone, Debug, Serialize)]
pub struct EvidenceUpload {
    pub result_uri: String,
    pub bundle_uri: String,
    pub bundle_sha256: String,
    pub bundle_size_bytes: u64,
    pub commands: Vec<PlannedCommand>,
}

pub fn upload_evidence(request: UploadEvidenceRequest<'_>) -> Result<EvidenceUpload> {
    let expanded = request.plan.expand()?;
    let cell = expanded
        .cells
        .iter()
        .find(|cell| cell.id == request.cell_id)
        .with_context(|| format!("plan has no cell {}", request.cell_id))?;
    let manifest_path = request.result_dir.join("result.json");
    let mut manifest = parse_evidence_manifest(&manifest_path)?;
    ensure!(
        manifest.plan_sha256 == expanded.plan_sha256,
        "plan fingerprint differs"
    );
    ensure!(manifest.cell == *cell, "expanded cell differs");
    verify_manifest_files(request.result_dir, &manifest)?;

    let temporary_dir = tempdir().context("failed to create evidence bundle directory")?;
    let bundle_path = temporary_dir.path().join("evidence.tar.zst");
    create_evidence_bundle(request.result_dir, &manifest, &bundle_path)?;
    let bundle = EvidenceFile {
        path: "evidence.tar.zst".to_string(),
        sha256: sha256_file(&bundle_path)?,
        size_bytes: bundle_path.metadata()?.len(),
    };
    manifest.bundle = Some(bundle.clone());
    crate::verify_evidence_manifest(&manifest)?;
    write_json(&manifest_path, &manifest)?;

    let bundle_uri = format!(
        "{}/bundles/sha256/{}/evidence.tar.zst",
        cell.result_uri, bundle.sha256
    );
    let manifest_uri = format!("{}/result.json", cell.result_uri);
    let commands = vec![
        PlannedCommand {
            program: request.gcloud.display().to_string(),
            arguments: vec![
                "storage".to_string(),
                "cp".to_string(),
                "--no-clobber".to_string(),
                "<generated-evidence-bundle>".to_string(),
                bundle_uri.clone(),
            ],
        },
        PlannedCommand {
            program: request.gcloud.display().to_string(),
            arguments: vec![
                "storage".to_string(),
                "cp".to_string(),
                "--no-clobber".to_string(),
                manifest_path.display().to_string(),
                manifest_uri.clone(),
            ],
        },
    ];
    if !request.dry_run {
        run_checked(
            request.gcloud,
            &[
                "storage",
                "cp",
                "--no-clobber",
                bundle_path.to_string_lossy().as_ref(),
                &bundle_uri,
            ],
        )?;
        run_planned(&commands[1])?;
    }
    Ok(EvidenceUpload {
        result_uri: cell.result_uri.clone(),
        bundle_uri,
        bundle_sha256: bundle.sha256,
        bundle_size_bytes: bundle.size_bytes,
        commands,
    })
}

pub(crate) fn create_evidence_bundle(
    result_dir: &Path,
    manifest: &crate::EvidenceManifest,
    output: &Path,
) -> Result<()> {
    let file = File::create(output)?;
    let encoder = zstd::Encoder::new(file, 3).context("failed to create zstd encoder")?;
    let mut archive = tar::Builder::new(encoder.auto_finish());
    for evidence in &manifest.files {
        let source = result_dir.join(&evidence.path);
        ensure_file_hash(&source, &evidence.sha256)?;
        archive
            .append_path_with_name(&source, &evidence.path)
            .with_context(|| format!("failed to archive {}", evidence.path))?;
    }
    archive
        .finish()
        .context("failed to finish evidence archive")
}

pub fn verify_evidence_bundle(
    manifest_path: &Path,
    bundle_path: &Path,
    extraction_root: &Path,
) -> Result<()> {
    let manifest = parse_evidence_manifest(manifest_path)?;
    let bundle = manifest
        .bundle
        .as_ref()
        .context("manifest has no evidence bundle")?;
    ensure_file_hash(bundle_path, &bundle.sha256)?;
    ensure!(
        bundle_path.metadata()?.len() == bundle.size_bytes,
        "bundle size differs"
    );
    ensure!(!extraction_root.exists(), "extraction root already exists");
    fs::create_dir_all(extraction_root)?;
    let file = File::open(bundle_path)?;
    let decoder = zstd::Decoder::new(file).context("failed to open zstd bundle")?;
    let mut archive = tar::Archive::new(decoder);
    for entry in archive.entries()? {
        let mut entry = entry?;
        let relative = entry.path()?.to_string_lossy().to_string();
        ensure!(
            is_safe_relative_path(&relative),
            "unsafe bundle entry {relative}"
        );
        let expected = manifest
            .files
            .iter()
            .find(|file| file.path == relative)
            .with_context(|| format!("bundle contains unlisted file {relative}"))?;
        let destination = extraction_root.join(&relative);
        if let Some(parent) = destination.parent() {
            fs::create_dir_all(parent)?;
        }
        let mut output = File::create(&destination)?;
        std::io::copy(&mut entry, &mut output)?;
        output.flush()?;
        ensure_file_hash(&destination, &expected.sha256)?;
        ensure!(
            destination.metadata()?.len() == expected.size_bytes,
            "file size differs"
        );
    }
    let extracted: BTreeSet<_> = WalkDir::new(extraction_root)
        .into_iter()
        .collect::<std::result::Result<Vec<_>, _>>()?
        .into_iter()
        .filter(|entry| entry.file_type().is_file())
        .map(|entry| {
            entry
                .path()
                .strip_prefix(extraction_root)
                .expect("walked path is under extraction root")
                .to_string_lossy()
                .to_string()
        })
        .collect();
    let expected: BTreeSet<_> = manifest
        .files
        .iter()
        .map(|file| file.path.clone())
        .collect();
    ensure!(
        extracted == expected,
        "bundle file set differs from manifest"
    );
    Ok(())
}

fn is_safe_relative_path(value: &str) -> bool {
    !value.is_empty()
        && !value.starts_with('/')
        && !value.contains("..")
        && !value.contains('\\')
        && !value
            .bytes()
            .any(|byte| byte == 0 || byte == b'\n' || byte == b'\r')
}

fn run_planned(command: &PlannedCommand) -> Result<Output> {
    let arguments: Vec<_> = command.arguments.iter().map(String::as_str).collect();
    run_checked(Path::new(&command.program), &arguments)
}

fn run_checked(program: &Path, arguments: &[&str]) -> Result<Output> {
    let output = Command::new(program)
        .args(arguments)
        .output()
        .with_context(|| format!("failed to start {}", program.display()))?;
    if !output.status.success() {
        bail!(
            "{} failed with {}: {}",
            program.display(),
            output.status,
            String::from_utf8_lossy(&output.stderr).trim()
        );
    }
    Ok(output)
}

fn validate_repository(value: &str) -> Result<()> {
    let parts: Vec<_> = value.split('/').collect();
    ensure!(parts.len() == 2, "repository must use owner/name form");
    validate_safe_name("repository owner", parts[0], 64)?;
    validate_safe_name("repository name", parts[1], 100)
}

fn validate_safe_name(field: &str, value: &str, max_len: usize) -> Result<()> {
    ensure!(
        !value.is_empty() && value.len() <= max_len,
        "{field} has invalid length"
    );
    ensure!(
        value
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || matches!(byte, b'.' | b'_' | b'-')),
        "{field} contains unsupported characters"
    );
    Ok(())
}

fn validate_commit(value: &str) -> Result<()> {
    ensure!(
        value.len() == 40
            && value
                .bytes()
                .all(|byte| byte.is_ascii_digit() || (b'a'..=b'f').contains(&byte)),
        "tooling_ref must be an immutable lowercase 40-character commit"
    );
    Ok(())
}

fn validate_git_ref(value: &str) -> Result<()> {
    ensure!(
        !value.is_empty()
            && value.len() <= 256
            && !value.starts_with('-')
            && !value.contains("..")
            && value.bytes().all(|byte| {
                byte.is_ascii_alphanumeric() || matches!(byte, b'/' | b'.' | b'_' | b'-')
            }),
        "workflow_ref is unsafe"
    );
    Ok(())
}

fn now_utc() -> String {
    Utc::now().to_rfc3339_opts(SecondsFormat::Secs, true)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn rejects_mutable_tooling_ref() {
        assert!(validate_commit("main").is_err());
        assert!(validate_commit(&"a".repeat(40)).is_ok());
    }

    #[test]
    fn rejects_unsafe_workflow_repository() {
        assert!(validate_repository("owner/repo").is_ok());
        assert!(validate_repository("owner/repo/extra").is_err());
        assert!(validate_repository("owner/repo\n--flag").is_err());
    }

    #[test]
    fn extracts_zstd_input_without_external_tar_or_zstd() {
        let root = tempdir().unwrap();
        let source = root.path().join("r8-arguments.txt");
        fs::write(&source, b"--release\n").unwrap();
        let archive_path = root.path().join("input.tar.zst");
        let encoder = zstd::Encoder::new(File::create(&archive_path).unwrap(), 1).unwrap();
        let mut archive = tar::Builder::new(encoder.auto_finish());
        archive
            .append_path_with_name(&source, "r8-arguments.txt")
            .unwrap();
        archive.finish().unwrap();
        drop(archive);

        let output = root.path().join("input");
        fs::create_dir(&output).unwrap();
        extract_input_archive(&archive_path, &output).unwrap();
        assert_eq!(
            fs::read(output.join("r8-arguments.txt")).unwrap(),
            b"--release\n"
        );
    }
}
