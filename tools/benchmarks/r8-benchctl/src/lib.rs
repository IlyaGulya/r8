// Copyright (c) 2026, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

use std::collections::{BTreeMap, BTreeSet};
use std::fs;
use std::io::{BufReader, Read};
use std::path::Path;

use anyhow::{Context, Result, bail, ensure};
use schemars::{JsonSchema, schema_for};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};

pub mod cell;
pub mod external;
pub mod report;

pub const PLAN_SCHEMA_VERSION: u32 = 1;
pub const EVIDENCE_SCHEMA_VERSION: u32 = 1;
pub const MAX_MATRIX_CELLS: usize = 240;

#[derive(Clone, Debug, Deserialize, Serialize, JsonSchema, PartialEq, Eq)]
#[serde(deny_unknown_fields)]
pub struct ExperimentPlan {
    pub schema_version: u32,
    pub experiment_id: String,
    pub storage: StorageSpec,
    pub max_parallel: u16,
    pub artifacts: BTreeMap<String, R8Artifact>,
    pub input: InputArtifact,
    pub comparisons: Vec<Comparison>,
    pub runtimes: Vec<RuntimeSpec>,
    pub timing: TimingSpec,
    #[serde(default)]
    pub diagnostics: DiagnosticsSpec,
    #[serde(default)]
    pub gradle: GradleSpec,
}

#[derive(Clone, Debug, Deserialize, Serialize, JsonSchema, PartialEq, Eq)]
#[serde(deny_unknown_fields)]
pub struct StorageSpec {
    pub root_uri: String,
}

#[derive(Clone, Debug, Deserialize, Serialize, JsonSchema, PartialEq, Eq)]
#[serde(deny_unknown_fields)]
pub struct R8Artifact {
    pub repository: String,
    pub commit: String,
    pub jar_sha256: String,
}

#[derive(Clone, Debug, Deserialize, Serialize, JsonSchema, PartialEq, Eq)]
#[serde(deny_unknown_fields)]
pub struct InputArtifact {
    pub archive_sha256: String,
    pub expected_outputs_sha256: String,
}

#[derive(Clone, Debug, Deserialize, Serialize, JsonSchema, PartialEq, Eq)]
#[serde(deny_unknown_fields)]
pub struct Comparison {
    pub id: String,
    pub control: String,
    pub candidate: String,
}

#[derive(Clone, Debug, Deserialize, Serialize, JsonSchema, PartialEq, Eq)]
#[serde(deny_unknown_fields)]
pub struct RuntimeSpec {
    pub id: String,
    pub java_distribution: String,
    pub java_version: String,
    pub gc: GarbageCollector,
    pub xmx: String,
    pub active_processors: u16,
    pub r8_threads: u16,
    pub runner_labels: Vec<String>,
}

#[derive(Clone, Copy, Debug, Deserialize, Serialize, JsonSchema, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum GarbageCollector {
    G1,
    Parallel,
}

#[derive(Clone, Debug, Deserialize, Serialize, JsonSchema, PartialEq, Eq)]
#[serde(deny_unknown_fields)]
pub struct TimingSpec {
    pub pairs: u16,
    #[serde(default = "default_cooldown_seconds")]
    pub cooldown_seconds: u16,
}

fn default_cooldown_seconds() -> u16 {
    15
}

#[derive(Clone, Debug, Deserialize, Serialize, JsonSchema, PartialEq, Eq)]
#[serde(deny_unknown_fields)]
pub struct DiagnosticsSpec {
    #[serde(default)]
    pub enabled: bool,
    #[serde(default = "default_diagnostic_max_parallel")]
    pub max_parallel: u16,
    #[serde(default)]
    pub jit_targets: Vec<String>,
}

fn default_diagnostic_max_parallel() -> u16 {
    1
}

impl Default for DiagnosticsSpec {
    fn default() -> Self {
        Self {
            enabled: false,
            max_parallel: default_diagnostic_max_parallel(),
            jit_targets: Vec::new(),
        }
    }
}

#[derive(Clone, Debug, Default, Deserialize, Serialize, JsonSchema, PartialEq, Eq)]
#[serde(deny_unknown_fields)]
pub struct GradleSpec {
    #[serde(default)]
    pub enabled: bool,
    #[serde(default)]
    pub android_commit: String,
    #[serde(default)]
    pub pairs: u16,
    #[serde(default)]
    pub cleanup_tasks: Vec<String>,
    #[serde(default)]
    pub tasks: Vec<String>,
    #[serde(default)]
    pub arguments: Vec<String>,
    #[serde(default)]
    pub github_cache_modes: Vec<GradleCacheMode>,
}

#[derive(Clone, Copy, Debug, Deserialize, Serialize, JsonSchema, PartialEq, Eq)]
#[serde(rename_all = "kebab-case")]
pub enum GradleCacheMode {
    All,
    ExcludeBuildCache,
    Disabled,
}

#[derive(Clone, Copy, Debug, Deserialize, Serialize, JsonSchema, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum CellKind {
    StandaloneTiming,
    StandaloneDiagnostic,
    GradleTiming,
}

#[derive(Clone, Copy, Debug, Deserialize, Serialize, JsonSchema, PartialEq, Eq)]
pub enum PairOrder {
    AB,
    BA,
    ABBA,
}

#[derive(Clone, Debug, Deserialize, Serialize, JsonSchema, PartialEq, Eq)]
#[serde(deny_unknown_fields)]
pub struct ExpandedCell {
    pub id: String,
    pub kind: CellKind,
    pub comparison_id: String,
    pub runtime_id: String,
    pub order: PairOrder,
    pub pair_index: u16,
    pub control_artifact: String,
    pub candidate_artifact: String,
    pub java_distribution: String,
    pub java_version: String,
    pub gc: GarbageCollector,
    pub xmx: String,
    pub active_processors: u16,
    pub r8_threads: u16,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub gradle_cache_mode: Option<GradleCacheMode>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub android_commit: Option<String>,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub gradle_cleanup_tasks: Vec<String>,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub gradle_tasks: Vec<String>,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub gradle_arguments: Vec<String>,
    pub runner_labels: Vec<String>,
    pub result_uri: String,
}

#[derive(Clone, Debug, Deserialize, Serialize, JsonSchema, PartialEq, Eq)]
#[serde(deny_unknown_fields)]
pub struct ExpandedPlan {
    pub schema_version: u32,
    pub experiment_id: String,
    pub plan_sha256: String,
    pub max_parallel: u16,
    pub cells: Vec<ExpandedCell>,
}

#[derive(Clone, Debug, Deserialize, Serialize, JsonSchema, PartialEq)]
#[serde(deny_unknown_fields)]
pub struct EvidenceManifest {
    pub schema_version: u32,
    pub experiment_id: String,
    pub plan_sha256: String,
    pub cell_id: String,
    pub cell: ExpandedCell,
    pub state: EvidenceState,
    pub started_utc: String,
    pub completed_utc: Option<String>,
    pub environment: BTreeMap<String, String>,
    pub runs: Vec<BenchmarkRun>,
    pub files: Vec<EvidenceFile>,
    pub bundle: Option<EvidenceFile>,
}

#[derive(Clone, Debug, Deserialize, Serialize, JsonSchema, PartialEq)]
#[serde(deny_unknown_fields)]
pub struct BenchmarkRun {
    pub ordinal: u16,
    pub variant: Variant,
    pub metrics: RunMetrics,
    pub outputs_sha256: BTreeMap<String, String>,
}

#[derive(Clone, Copy, Debug, Deserialize, Serialize, JsonSchema, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum Variant {
    Control,
    Candidate,
}

#[derive(Clone, Debug, Deserialize, Serialize, JsonSchema, PartialEq)]
#[serde(deny_unknown_fields)]
pub struct RunMetrics {
    pub wall_seconds: f64,
    pub user_seconds: f64,
    pub system_seconds: f64,
    pub max_rss_bytes: u64,
    pub peak_footprint_bytes: u64,
    pub instructions: Option<u64>,
    pub cycles: Option<u64>,
}

#[derive(Clone, Copy, Debug, Deserialize, Serialize, JsonSchema, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum EvidenceState {
    Running,
    Complete,
    Failed,
    Invalid,
}

#[derive(Clone, Debug, Deserialize, Serialize, JsonSchema, PartialEq, Eq)]
#[serde(deny_unknown_fields)]
pub struct EvidenceFile {
    pub path: String,
    pub sha256: String,
    pub size_bytes: u64,
}

#[derive(Clone, Debug, Deserialize, Serialize, JsonSchema, PartialEq, Eq)]
#[serde(deny_unknown_fields)]
pub struct R8ArtifactManifest {
    pub schema_version: u32,
    pub artifact_id: String,
    pub repository: String,
    pub commit: String,
    pub jar_sha256: String,
    pub jar_size_bytes: u64,
    pub created_utc: String,
}

#[derive(Clone, Debug, Deserialize, Serialize, JsonSchema, PartialEq, Eq)]
#[serde(deny_unknown_fields)]
pub struct InputArtifactManifest {
    pub schema_version: u32,
    pub archive_sha256: String,
    pub archive_size_bytes: u64,
    pub expected_outputs_sha256: String,
    pub expected_outputs_size_bytes: u64,
    pub created_utc: String,
}

#[derive(Serialize)]
pub struct GitHubMatrix<'a> {
    pub include: &'a [ExpandedCell],
}

impl ExperimentPlan {
    pub fn from_toml_file(path: &Path) -> Result<Self> {
        let text = fs::read_to_string(path)
            .with_context(|| format!("failed to read experiment plan {}", path.display()))?;
        let plan: Self = toml::from_str(&text)
            .with_context(|| format!("failed to parse experiment plan {}", path.display()))?;
        plan.validate()?;
        Ok(plan)
    }

    pub fn validate(&self) -> Result<()> {
        ensure!(
            self.schema_version == PLAN_SCHEMA_VERSION,
            "unsupported schema_version {}; expected {}",
            self.schema_version,
            PLAN_SCHEMA_VERSION
        );
        validate_id("experiment_id", &self.experiment_id)?;
        validate_gcs_root(&self.storage.root_uri)?;
        ensure!(
            (1..=64).contains(&self.max_parallel),
            "max_parallel must be between 1 and 64"
        );
        ensure!(!self.artifacts.is_empty(), "artifacts must not be empty");
        for (id, artifact) in &self.artifacts {
            validate_id("artifact id", id)?;
            validate_repository(&artifact.repository)?;
            validate_git_commit(&artifact.commit)?;
            validate_sha256("artifact jar_sha256", &artifact.jar_sha256)?;
        }
        validate_sha256("input archive_sha256", &self.input.archive_sha256)?;
        validate_sha256(
            "input expected_outputs_sha256",
            &self.input.expected_outputs_sha256,
        )?;
        ensure!(
            !self.comparisons.is_empty(),
            "comparisons must not be empty"
        );
        ensure!(!self.runtimes.is_empty(), "runtimes must not be empty");
        ensure!(
            (1..=20).contains(&self.timing.pairs),
            "timing.pairs must be between 1 and 20"
        );
        ensure!(
            self.timing.cooldown_seconds <= 600,
            "timing.cooldown_seconds must not exceed 600"
        );

        let mut comparison_ids = BTreeSet::new();
        for comparison in &self.comparisons {
            validate_id("comparison id", &comparison.id)?;
            ensure!(
                comparison_ids.insert(comparison.id.as_str()),
                "duplicate comparison id {}",
                comparison.id
            );
            ensure!(
                comparison.control != comparison.candidate,
                "comparison {} has identical control and candidate",
                comparison.id
            );
            ensure!(
                self.artifacts.contains_key(&comparison.control),
                "comparison {} references missing control artifact {}",
                comparison.id,
                comparison.control
            );
            ensure!(
                self.artifacts.contains_key(&comparison.candidate),
                "comparison {} references missing candidate artifact {}",
                comparison.id,
                comparison.candidate
            );
        }

        let mut runtime_ids = BTreeSet::new();
        for runtime in &self.runtimes {
            validate_id("runtime id", &runtime.id)?;
            ensure!(
                runtime_ids.insert(runtime.id.as_str()),
                "duplicate runtime id {}",
                runtime.id
            );
            validate_safe_token("java_distribution", &runtime.java_distribution, 32)?;
            validate_safe_token("java_version", &runtime.java_version, 64)?;
            validate_heap(&runtime.xmx)?;
            ensure!(
                (1..=128).contains(&runtime.active_processors),
                "runtime {} active_processors must be between 1 and 128",
                runtime.id
            );
            ensure!(
                (1..=128).contains(&runtime.r8_threads),
                "runtime {} r8_threads must be between 1 and 128",
                runtime.id
            );
            ensure!(
                !runtime.runner_labels.is_empty() && runtime.runner_labels.len() <= 8,
                "runtime {} runner_labels must contain between 1 and 8 labels",
                runtime.id
            );
            for label in &runtime.runner_labels {
                validate_safe_token("runner label", label, 64)?;
            }
        }

        ensure!(
            self.diagnostics.jit_targets.len() <= 64,
            "diagnostics.jit_targets must not contain more than 64 entries"
        );
        ensure!(
            (1..=self.max_parallel).contains(&self.diagnostics.max_parallel),
            "diagnostics.max_parallel must be between 1 and plan max_parallel"
        );
        for target in &self.diagnostics.jit_targets {
            validate_text("diagnostic JIT target", target, 256)?;
        }

        if self.gradle.enabled {
            validate_git_commit(&self.gradle.android_commit)?;
            ensure!(
                (1..=10).contains(&self.gradle.pairs),
                "gradle.pairs must be between 1 and 10 when Gradle mode is enabled"
            );
            ensure!(
                !self.gradle.cleanup_tasks.is_empty() && self.gradle.cleanup_tasks.len() <= 8,
                "gradle.cleanup_tasks must contain between 1 and 8 entries"
            );
            for task in &self.gradle.cleanup_tasks {
                validate_gradle_task(task)?;
            }
            ensure!(
                !self.gradle.tasks.is_empty() && self.gradle.tasks.len() <= 16,
                "gradle.tasks must contain between 1 and 16 entries"
            );
            for task in &self.gradle.tasks {
                validate_gradle_task(task)?;
            }
            ensure!(
                self.gradle.arguments.len() <= 64,
                "gradle.arguments must not contain more than 64 entries"
            );
            for argument in &self.gradle.arguments {
                validate_text("Gradle argument", argument, 512)?;
            }
            ensure!(
                !self.gradle.github_cache_modes.is_empty(),
                "gradle.github_cache_modes must not be empty when Gradle mode is enabled"
            );
        } else {
            ensure!(
                self.gradle.pairs == 0
                    && self.gradle.cleanup_tasks.is_empty()
                    && self.gradle.tasks.is_empty()
                    && self.gradle.arguments.is_empty()
                    && self.gradle.github_cache_modes.is_empty()
                    && self.gradle.android_commit.is_empty(),
                "disabled Gradle mode must not contain Gradle configuration"
            );
        }

        let cell_count = self.expected_cell_count();
        ensure!(cell_count > 0, "plan expands to no cells");
        ensure!(
            cell_count <= MAX_MATRIX_CELLS,
            "plan expands to {cell_count} cells; maximum is {MAX_MATRIX_CELLS}"
        );
        Ok(())
    }

    pub fn expected_cell_count(&self) -> usize {
        let dimensions = self.comparisons.len() * self.runtimes.len();
        let timing = dimensions * usize::from(self.timing.pairs);
        let diagnostics = usize::from(self.diagnostics.enabled) * dimensions;
        let gradle = if self.gradle.enabled {
            dimensions * usize::from(self.gradle.pairs) * self.gradle.github_cache_modes.len()
        } else {
            0
        };
        timing + diagnostics + gradle
    }

    pub fn fingerprint(&self) -> Result<String> {
        self.validate()?;
        let canonical = serde_json::to_vec(self).context("failed to serialize canonical plan")?;
        Ok(sha256_bytes(&canonical))
    }

    pub fn expand(&self) -> Result<ExpandedPlan> {
        self.validate()?;
        let plan_sha256 = self.fingerprint()?;
        let result_root = format!(
            "{}/experiments/{}/{plan_sha256}",
            self.storage.root_uri.trim_end_matches('/'),
            self.experiment_id
        );
        let mut cells = Vec::with_capacity(self.expected_cell_count());
        for comparison in &self.comparisons {
            for runtime in &self.runtimes {
                for pair_index in 1..=self.timing.pairs {
                    let order = alternating_order(pair_index);
                    push_cell(
                        &mut cells,
                        &result_root,
                        CellSeed {
                            kind: CellKind::StandaloneTiming,
                            comparison,
                            runtime,
                            order,
                            pair_index,
                            cache_mode: None,
                            gradle: None,
                        },
                    );
                }
                if self.diagnostics.enabled {
                    push_cell(
                        &mut cells,
                        &result_root,
                        CellSeed {
                            kind: CellKind::StandaloneDiagnostic,
                            comparison,
                            runtime,
                            order: PairOrder::ABBA,
                            pair_index: 1,
                            cache_mode: None,
                            gradle: None,
                        },
                    );
                }
                if self.gradle.enabled {
                    for cache_mode in &self.gradle.github_cache_modes {
                        for pair_index in 1..=self.gradle.pairs {
                            push_cell(
                                &mut cells,
                                &result_root,
                                CellSeed {
                                    kind: CellKind::GradleTiming,
                                    comparison,
                                    runtime,
                                    order: alternating_order(pair_index),
                                    pair_index,
                                    cache_mode: Some(*cache_mode),
                                    gradle: Some(&self.gradle),
                                },
                            );
                        }
                    }
                }
            }
        }
        let unique: BTreeSet<_> = cells.iter().map(|cell| cell.id.as_str()).collect();
        ensure!(
            unique.len() == cells.len(),
            "expanded cell IDs are not unique"
        );
        Ok(ExpandedPlan {
            schema_version: PLAN_SCHEMA_VERSION,
            experiment_id: self.experiment_id.clone(),
            plan_sha256,
            max_parallel: self.max_parallel,
            cells,
        })
    }

    pub fn artifact_uri(&self, artifact_id: &str) -> Result<String> {
        let artifact = self
            .artifacts
            .get(artifact_id)
            .with_context(|| format!("unknown artifact {artifact_id}"))?;
        Ok(format!(
            "{}/artifacts/sha256/{}/r8.jar",
            self.storage.root_uri.trim_end_matches('/'),
            artifact.jar_sha256
        ))
    }

    pub fn input_uri(&self) -> String {
        format!(
            "{}/inputs/sha256/{}/input.tar.zst",
            self.storage.root_uri.trim_end_matches('/'),
            self.input.archive_sha256
        )
    }

    pub fn expected_outputs_uri(&self) -> String {
        format!(
            "{}/inputs/sha256/{}/expected-outputs.tsv",
            self.storage.root_uri.trim_end_matches('/'),
            self.input.archive_sha256
        )
    }

    pub fn artifact_manifest_uri(&self, artifact_id: &str) -> Result<String> {
        let artifact = self
            .artifacts
            .get(artifact_id)
            .with_context(|| format!("unknown artifact {artifact_id}"))?;
        Ok(format!(
            "{}/artifacts/sha256/{}/manifest.json",
            self.storage.root_uri.trim_end_matches('/'),
            artifact.jar_sha256
        ))
    }

    pub fn input_manifest_uri(&self) -> String {
        format!(
            "{}/inputs/sha256/{}/manifest.json",
            self.storage.root_uri.trim_end_matches('/'),
            self.input.archive_sha256
        )
    }

    pub fn plan_uri(&self) -> Result<String> {
        let fingerprint = self.fingerprint()?;
        Ok(format!(
            "{}/experiments/{}/{fingerprint}/plan.toml",
            self.storage.root_uri.trim_end_matches('/'),
            self.experiment_id
        ))
    }

    pub fn result_root_uri(&self) -> Result<String> {
        Ok(format!(
            "{}/experiments/{}/{}",
            self.storage.root_uri.trim_end_matches('/'),
            self.experiment_id,
            self.fingerprint()?
        ))
    }
}

struct CellSeed<'a> {
    kind: CellKind,
    comparison: &'a Comparison,
    runtime: &'a RuntimeSpec,
    order: PairOrder,
    pair_index: u16,
    cache_mode: Option<GradleCacheMode>,
    gradle: Option<&'a GradleSpec>,
}

fn push_cell(cells: &mut Vec<ExpandedCell>, result_root: &str, seed: CellSeed<'_>) {
    let kind = match seed.kind {
        CellKind::StandaloneTiming => "standalone-timing",
        CellKind::StandaloneDiagnostic => "standalone-diagnostic",
        CellKind::GradleTiming => "gradle-timing",
    };
    let order = match seed.order {
        PairOrder::AB => "ab",
        PairOrder::BA => "ba",
        PairOrder::ABBA => "abba",
    };
    let cache = seed.cache_mode.map(|mode| match mode {
        GradleCacheMode::All => "all",
        GradleCacheMode::ExcludeBuildCache => "exclude-build-cache",
        GradleCacheMode::Disabled => "disabled",
    });
    let cache_suffix = cache.map(|value| format!("-{value}")).unwrap_or_default();
    let id = format!(
        "{}-{}-{kind}{cache_suffix}-p{:02}-{order}",
        seed.comparison.id, seed.runtime.id, seed.pair_index
    );
    cells.push(ExpandedCell {
        result_uri: format!("{result_root}/cells/{id}"),
        id,
        kind: seed.kind,
        comparison_id: seed.comparison.id.clone(),
        runtime_id: seed.runtime.id.clone(),
        order: seed.order,
        pair_index: seed.pair_index,
        control_artifact: seed.comparison.control.clone(),
        candidate_artifact: seed.comparison.candidate.clone(),
        java_distribution: seed.runtime.java_distribution.clone(),
        java_version: seed.runtime.java_version.clone(),
        gc: seed.runtime.gc,
        xmx: seed.runtime.xmx.clone(),
        active_processors: seed.runtime.active_processors,
        r8_threads: seed.runtime.r8_threads,
        gradle_cache_mode: seed.cache_mode,
        android_commit: seed.gradle.map(|gradle| gradle.android_commit.clone()),
        gradle_cleanup_tasks: seed
            .gradle
            .map(|gradle| gradle.cleanup_tasks.clone())
            .unwrap_or_default(),
        gradle_tasks: seed
            .gradle
            .map(|gradle| gradle.tasks.clone())
            .unwrap_or_default(),
        gradle_arguments: seed
            .gradle
            .map(|gradle| gradle.arguments.clone())
            .unwrap_or_default(),
        runner_labels: seed.runtime.runner_labels.clone(),
    });
}

fn alternating_order(pair_index: u16) -> PairOrder {
    if pair_index % 2 == 1 {
        PairOrder::AB
    } else {
        PairOrder::BA
    }
}

pub fn experiment_plan_schema() -> serde_json::Value {
    serde_json::to_value(schema_for!(ExperimentPlan)).expect("schema serialization must succeed")
}

pub fn evidence_manifest_schema() -> serde_json::Value {
    serde_json::to_value(schema_for!(EvidenceManifest)).expect("schema serialization must succeed")
}

pub fn sha256_file(path: &Path) -> Result<String> {
    let file =
        fs::File::open(path).with_context(|| format!("failed to open {}", path.display()))?;
    let mut reader = BufReader::with_capacity(1024 * 1024, file);
    let mut digest = Sha256::new();
    let mut buffer = [0_u8; 1024 * 1024];
    loop {
        let count = reader
            .read(&mut buffer)
            .with_context(|| format!("failed to read {}", path.display()))?;
        if count == 0 {
            break;
        }
        digest.update(&buffer[..count]);
    }
    Ok(hex_digest(digest.finalize()))
}

fn sha256_bytes(bytes: &[u8]) -> String {
    hex_digest(Sha256::digest(bytes))
}

fn hex_digest(digest: impl AsRef<[u8]>) -> String {
    let mut output = String::with_capacity(64);
    for byte in digest.as_ref() {
        use std::fmt::Write;
        write!(&mut output, "{byte:02x}").expect("writing to String must succeed");
    }
    output
}

fn validate_id(field: &str, value: &str) -> Result<()> {
    ensure!(
        !value.is_empty() && value.len() <= 64,
        "{field} must contain between 1 and 64 characters"
    );
    ensure!(
        value
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || matches!(byte, b'.' | b'_' | b'-')),
        "{field} contains unsupported characters: {value}"
    );
    ensure!(
        !value.starts_with('.') && !value.contains(".."),
        "{field} contains an unsafe path segment: {value}"
    );
    Ok(())
}

fn validate_sha256(field: &str, value: &str) -> Result<()> {
    ensure!(
        value.len() == 64
            && value
                .bytes()
                .all(|byte| byte.is_ascii_digit() || (b'a'..=b'f').contains(&byte)),
        "{field} must be a lowercase 64-character SHA256"
    );
    Ok(())
}

fn validate_git_commit(value: &str) -> Result<()> {
    ensure!(
        value.len() == 40
            && value
                .bytes()
                .all(|byte| byte.is_ascii_digit() || (b'a'..=b'f').contains(&byte)),
        "artifact commit must be a lowercase 40-character Git commit"
    );
    Ok(())
}

fn validate_repository(value: &str) -> Result<()> {
    let mut parts = value.split('/');
    let owner = parts.next().unwrap_or_default();
    let repository = parts.next().unwrap_or_default();
    ensure!(
        !owner.is_empty() && !repository.is_empty() && parts.next().is_none(),
        "repository must use owner/name form"
    );
    validate_safe_token("repository owner", owner, 64)?;
    validate_safe_token("repository name", repository, 100)
}

fn validate_gcs_root(value: &str) -> Result<()> {
    let path = value
        .strip_prefix("gs://")
        .context("storage.root_uri must start with gs://")?;
    ensure!(!path.is_empty(), "storage.root_uri must include a bucket");
    ensure!(
        !path.ends_with('/') && !path.contains("//") && !path.contains(".."),
        "storage.root_uri must be normalized and must not contain unsafe segments"
    );
    ensure!(
        path.bytes().all(|byte| {
            byte.is_ascii_alphanumeric() || matches!(byte, b'.' | b'_' | b'-' | b'/')
        }),
        "storage.root_uri contains unsupported characters"
    );
    Ok(())
}

fn validate_heap(value: &str) -> Result<()> {
    let suffix = value
        .chars()
        .last()
        .context("runtime xmx must not be empty")?;
    ensure!(
        matches!(suffix, 'm' | 'g'),
        "runtime xmx must end in m or g"
    );
    let amount = &value[..value.len() - 1];
    let amount: u32 = amount
        .parse()
        .context("runtime xmx must start with an integer")?;
    ensure!(amount > 0, "runtime xmx must be positive");
    Ok(())
}

fn validate_safe_token(field: &str, value: &str, max_len: usize) -> Result<()> {
    ensure!(
        !value.is_empty() && value.len() <= max_len,
        "{field} must contain between 1 and {max_len} characters"
    );
    ensure!(
        value.bytes().all(|byte| {
            byte.is_ascii_alphanumeric() || matches!(byte, b'.' | b'_' | b'-' | b':')
        }),
        "{field} contains unsupported characters: {value}"
    );
    Ok(())
}

fn validate_text(field: &str, value: &str, max_len: usize) -> Result<()> {
    ensure!(!value.is_empty(), "{field} must not be empty");
    ensure!(value.len() <= max_len, "{field} exceeds {max_len} bytes");
    ensure!(
        !value
            .bytes()
            .any(|byte| byte == 0 || byte == b'\n' || byte == b'\r'),
        "{field} must not contain NUL or line breaks"
    );
    Ok(())
}

fn validate_gradle_task(value: &str) -> Result<()> {
    validate_text("Gradle task", value, 256)?;
    ensure!(
        value.starts_with(':')
            && value
                .bytes()
                .all(|byte| byte.is_ascii_alphanumeric() || matches!(byte, b':' | b'_' | b'-')),
        "Gradle task must be an absolute task path: {value}"
    );
    Ok(())
}

pub fn write_json(path: &Path, value: &impl Serialize) -> Result<()> {
    let parent = path.parent().context("output path has no parent")?;
    fs::create_dir_all(parent)
        .with_context(|| format!("failed to create output directory {}", parent.display()))?;
    let bytes = serde_json::to_vec_pretty(value).context("failed to serialize JSON")?;
    fs::write(path, bytes).with_context(|| format!("failed to write {}", path.display()))
}

pub fn verify_evidence_manifest(manifest: &EvidenceManifest) -> Result<()> {
    ensure!(
        manifest.schema_version == EVIDENCE_SCHEMA_VERSION,
        "unsupported evidence schema version"
    );
    validate_id("experiment_id", &manifest.experiment_id)?;
    validate_sha256("plan_sha256", &manifest.plan_sha256)?;
    validate_id("cell_id", &manifest.cell_id)?;
    ensure!(
        manifest.cell_id == manifest.cell.id,
        "evidence cell_id does not match embedded cell"
    );
    ensure!(
        !manifest.started_utc.is_empty(),
        "evidence started_utc must not be empty"
    );
    if manifest.state == EvidenceState::Complete {
        ensure!(
            manifest.completed_utc.is_some(),
            "complete evidence must have completed_utc"
        );
        ensure!(
            !manifest.files.is_empty(),
            "complete evidence must list files"
        );
        let expected_variants: &[Variant] = match manifest.cell.order {
            PairOrder::AB => &[Variant::Control, Variant::Candidate],
            PairOrder::BA => &[Variant::Candidate, Variant::Control],
            PairOrder::ABBA => &[
                Variant::Control,
                Variant::Candidate,
                Variant::Candidate,
                Variant::Control,
            ],
        };
        ensure!(
            manifest.runs.len() == expected_variants.len(),
            "complete evidence has {} runs; expected {} for {:?}",
            manifest.runs.len(),
            expected_variants.len(),
            manifest.cell.order
        );
        for (index, (run, expected_variant)) in manifest
            .runs
            .iter()
            .zip(expected_variants.iter())
            .enumerate()
        {
            ensure!(
                usize::from(run.ordinal) == index + 1,
                "run ordinals must be contiguous and one-based"
            );
            ensure!(
                run.variant == *expected_variant,
                "run {} variant does not match {:?} order",
                index + 1,
                manifest.cell.order
            );
            ensure!(
                run.metrics.wall_seconds.is_finite() && run.metrics.wall_seconds > 0.0,
                "run {} has invalid wall_seconds",
                index + 1
            );
            ensure!(
                run.metrics.user_seconds.is_finite() && run.metrics.user_seconds >= 0.0,
                "run {} has invalid user_seconds",
                index + 1
            );
            ensure!(
                run.metrics.system_seconds.is_finite() && run.metrics.system_seconds >= 0.0,
                "run {} has invalid system_seconds",
                index + 1
            );
            ensure!(
                !run.outputs_sha256.is_empty(),
                "run {} has no exact output hashes",
                index + 1
            );
            for hash in run.outputs_sha256.values() {
                validate_sha256("run output SHA256", hash)?;
            }
        }
        let first_outputs = &manifest.runs[0].outputs_sha256;
        ensure!(
            manifest
                .runs
                .iter()
                .all(|run| run.outputs_sha256 == *first_outputs),
            "exact output hashes differ between runs"
        );
    }
    let mut paths = BTreeSet::new();
    for file in &manifest.files {
        ensure!(
            paths.insert(file.path.as_str()),
            "duplicate evidence path {}",
            file.path
        );
        ensure!(
            !file.path.is_empty()
                && !file.path.starts_with('/')
                && !file.path.contains("..")
                && !file.path.contains('\\'),
            "unsafe evidence path {}",
            file.path
        );
        validate_sha256("evidence file sha256", &file.sha256)?;
    }
    if let Some(bundle) = &manifest.bundle {
        ensure!(
            bundle.path == "evidence.tar.zst",
            "evidence bundle must be named evidence.tar.zst"
        );
        validate_sha256("evidence bundle sha256", &bundle.sha256)?;
        ensure!(bundle.size_bytes > 0, "evidence bundle must not be empty");
    }
    Ok(())
}

pub fn parse_evidence_manifest(path: &Path) -> Result<EvidenceManifest> {
    let bytes = fs::read(path).with_context(|| format!("failed to read {}", path.display()))?;
    let manifest: EvidenceManifest = serde_json::from_slice(&bytes)
        .with_context(|| format!("failed to parse {}", path.display()))?;
    verify_evidence_manifest(&manifest)?;
    Ok(manifest)
}

pub fn ensure_file_hash(path: &Path, expected: &str) -> Result<()> {
    validate_sha256("expected SHA256", expected)?;
    let actual = sha256_file(path)?;
    if actual != expected {
        bail!(
            "SHA256 mismatch for {}: expected {}, actual {}",
            path.display(),
            expected,
            actual
        );
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Write as _;

    fn sha(character: char) -> String {
        std::iter::repeat_n(character, 64).collect()
    }

    fn commit(character: char) -> String {
        std::iter::repeat_n(character, 40).collect()
    }

    #[test]
    fn hashes_files_without_changing_digest_semantics() {
        let mut file = tempfile::NamedTempFile::new().unwrap();
        file.write_all(b"large-input-stream").unwrap();
        file.flush().unwrap();
        assert_eq!(
            sha256_file(file.path()).unwrap(),
            sha256_bytes(b"large-input-stream")
        );
    }

    fn sample_plan() -> ExperimentPlan {
        ExperimentPlan {
            schema_version: PLAN_SCHEMA_VERSION,
            experiment_id: "example-ci".to_string(),
            storage: StorageSpec {
                root_uri: "gs://example-bucket/r8-benchmark".to_string(),
            },
            max_parallel: 10,
            artifacts: BTreeMap::from([
                (
                    "control".to_string(),
                    R8Artifact {
                        repository: "example/r8".to_string(),
                        commit: commit('a'),
                        jar_sha256: sha('b'),
                    },
                ),
                (
                    "candidate".to_string(),
                    R8Artifact {
                        repository: "example/r8".to_string(),
                        commit: commit('c'),
                        jar_sha256: sha('d'),
                    },
                ),
            ]),
            input: InputArtifact {
                archive_sha256: sha('e'),
                expected_outputs_sha256: sha('f'),
            },
            comparisons: vec![Comparison {
                id: "candidate".to_string(),
                control: "control".to_string(),
                candidate: "candidate".to_string(),
            }],
            runtimes: vec![RuntimeSpec {
                id: "zulu21-g1".to_string(),
                java_distribution: "zulu".to_string(),
                java_version: "21.0.6".to_string(),
                gc: GarbageCollector::G1,
                xmx: "22g".to_string(),
                active_processors: 10,
                r8_threads: 10,
                runner_labels: vec!["self-hosted".to_string(), "linux".to_string()],
            }],
            timing: TimingSpec {
                pairs: 3,
                cooldown_seconds: 15,
            },
            diagnostics: DiagnosticsSpec {
                enabled: true,
                max_parallel: 1,
                jit_targets: vec!["LinearScanRegisterAllocator".to_string()],
            },
            gradle: GradleSpec::default(),
        }
    }

    #[test]
    fn expands_balanced_standalone_schedule() {
        let plan = sample_plan();
        let expanded = plan.expand().unwrap();
        assert_eq!(expanded.cells.len(), 4);
        assert_eq!(expanded.cells[0].order, PairOrder::AB);
        assert_eq!(expanded.cells[1].order, PairOrder::BA);
        assert_eq!(expanded.cells[2].order, PairOrder::AB);
        assert_eq!(expanded.cells[3].order, PairOrder::ABBA);
        assert_eq!(expanded.cells[0].kind, CellKind::StandaloneTiming);
        assert_eq!(expanded.cells[3].kind, CellKind::StandaloneDiagnostic);
        assert!(expanded.cells[0].result_uri.contains(&expanded.plan_sha256));
    }

    #[test]
    fn fingerprint_and_expansion_are_deterministic() {
        let plan = sample_plan();
        assert_eq!(plan.fingerprint().unwrap(), plan.fingerprint().unwrap());
        assert_eq!(plan.expand().unwrap(), plan.expand().unwrap());
    }

    #[test]
    fn rejects_unknown_toml_field() {
        let text = toml::to_string(&sample_plan()).unwrap() + "\nunknown = true\n";
        assert!(toml::from_str::<ExperimentPlan>(&text).is_err());
    }

    #[test]
    fn rejects_missing_artifact() {
        let mut plan = sample_plan();
        plan.comparisons[0].candidate = "missing".to_string();
        assert!(
            plan.validate()
                .unwrap_err()
                .to_string()
                .contains("missing candidate")
        );
    }

    #[test]
    fn rejects_oversized_matrix() {
        let mut plan = sample_plan();
        plan.diagnostics.enabled = false;
        plan.timing.pairs = 20;
        for index in 0..12 {
            let id = format!("runtime-{index}");
            let mut runtime = plan.runtimes[0].clone();
            runtime.id = id;
            plan.runtimes.push(runtime);
        }
        assert!(plan.validate().unwrap_err().to_string().contains("maximum"));
    }

    #[test]
    fn rejects_unsafe_storage_and_evidence_paths() {
        let mut plan = sample_plan();
        plan.storage.root_uri = "gs://bucket/../foreign".to_string();
        assert!(plan.validate().is_err());

        let manifest = EvidenceManifest {
            schema_version: EVIDENCE_SCHEMA_VERSION,
            experiment_id: "experiment".to_string(),
            plan_sha256: sha('a'),
            cell_id: "cell".to_string(),
            cell: ExpandedCell {
                id: "cell".to_string(),
                kind: CellKind::StandaloneTiming,
                comparison_id: "comparison".to_string(),
                runtime_id: "runtime".to_string(),
                order: PairOrder::AB,
                pair_index: 1,
                control_artifact: "control".to_string(),
                candidate_artifact: "candidate".to_string(),
                java_distribution: "zulu".to_string(),
                java_version: "21.0.6".to_string(),
                gc: GarbageCollector::G1,
                xmx: "22g".to_string(),
                active_processors: 10,
                r8_threads: 10,
                gradle_cache_mode: None,
                android_commit: None,
                gradle_cleanup_tasks: Vec::new(),
                gradle_tasks: Vec::new(),
                gradle_arguments: Vec::new(),
                runner_labels: vec!["runner".to_string()],
                result_uri: "gs://bucket/results/cell".to_string(),
            },
            state: EvidenceState::Complete,
            started_utc: "2026-08-01T00:00:00Z".to_string(),
            completed_utc: Some("2026-08-01T01:00:00Z".to_string()),
            environment: BTreeMap::new(),
            runs: vec![
                BenchmarkRun {
                    ordinal: 1,
                    variant: Variant::Control,
                    metrics: RunMetrics {
                        wall_seconds: 1.0,
                        user_seconds: 0.9,
                        system_seconds: 0.1,
                        max_rss_bytes: 1,
                        peak_footprint_bytes: 1,
                        instructions: None,
                        cycles: None,
                    },
                    outputs_sha256: BTreeMap::from([("out.zip".to_string(), sha('c'))]),
                },
                BenchmarkRun {
                    ordinal: 2,
                    variant: Variant::Candidate,
                    metrics: RunMetrics {
                        wall_seconds: 1.0,
                        user_seconds: 0.9,
                        system_seconds: 0.1,
                        max_rss_bytes: 1,
                        peak_footprint_bytes: 1,
                        instructions: None,
                        cycles: None,
                    },
                    outputs_sha256: BTreeMap::from([("out.zip".to_string(), sha('c'))]),
                },
            ],
            files: vec![EvidenceFile {
                path: "../foreign".to_string(),
                sha256: sha('b'),
                size_bytes: 1,
            }],
            bundle: None,
        };
        assert!(verify_evidence_manifest(&manifest).is_err());
    }

    #[test]
    fn gradle_cells_expand_over_cache_modes() {
        let mut plan = sample_plan();
        plan.diagnostics.enabled = false;
        plan.gradle = GradleSpec {
            enabled: true,
            android_commit: commit('f'),
            pairs: 2,
            cleanup_tasks: vec![":clean".to_string()],
            tasks: vec![":app:assembleGmsDebug".to_string()],
            arguments: vec![
                "-Pci=true".to_string(),
                "-PdebugMinifyEnabled=true".to_string(),
            ],
            github_cache_modes: vec![GradleCacheMode::All, GradleCacheMode::Disabled],
        };
        let expanded = plan.expand().unwrap();
        assert_eq!(expanded.cells.len(), 7);
        assert_eq!(
            expanded
                .cells
                .iter()
                .filter(|cell| cell.kind == CellKind::GradleTiming)
                .count(),
            4
        );
    }
}
