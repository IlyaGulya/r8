// Copyright (c) 2026, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

use std::collections::{BTreeMap, BTreeSet};
use std::fs::{self, File};
use std::io::{BufRead, BufReader};
use std::path::{Path, PathBuf};
use std::process::{Command, ExitStatus, Output, Stdio};
use std::thread;
use std::time::{Duration, Instant};

use anyhow::{Context, Result, bail, ensure};
use chrono::{SecondsFormat, Utc};
use walkdir::WalkDir;

use crate::{
    BenchmarkRun, CellKind, EvidenceFile, EvidenceManifest, EvidenceState, ExpandedCell,
    ExperimentPlan, GarbageCollector, PairOrder, RunMetrics, Variant, ensure_file_hash,
    parse_evidence_manifest, sha256_file, verify_evidence_manifest, write_json,
};

pub struct RunCellRequest<'a> {
    pub plan: &'a ExperimentPlan,
    pub cell_id: &'a str,
    pub control_jar: &'a Path,
    pub candidate_jar: &'a Path,
    pub input_archive: &'a Path,
    pub input_dir: &'a Path,
    pub expected_outputs: &'a Path,
    pub runner: &'a Path,
    pub java_bin: &'a Path,
    pub result_dir: &'a Path,
    pub resume: bool,
}

pub struct RunGradleCellRequest<'a> {
    pub plan: &'a ExperimentPlan,
    pub cell_id: &'a str,
    pub control_jar: &'a Path,
    pub candidate_jar: &'a Path,
    pub project_dir: &'a Path,
    pub gradlew: &'a Path,
    pub java_bin: &'a Path,
    pub result_dir: &'a Path,
    pub resume: bool,
}

struct CellLock {
    path: PathBuf,
}

impl Drop for CellLock {
    fn drop(&mut self) {
        let _ = fs::remove_dir(&self.path);
    }
}

pub fn run_cell(request: RunCellRequest<'_>) -> Result<EvidenceManifest> {
    let expanded = request.plan.expand()?;
    let cell = expanded
        .cells
        .iter()
        .find(|cell| cell.id == request.cell_id)
        .with_context(|| format!("plan has no cell {}", request.cell_id))?
        .clone();
    ensure!(
        matches!(
            cell.kind,
            CellKind::StandaloneTiming | CellKind::StandaloneDiagnostic
        ),
        "cell {} is not a standalone cell",
        cell.id
    );
    preflight(&request, &cell)?;

    let manifest_path = request.result_dir.join("result.json");
    if manifest_path.is_file() {
        let existing = parse_evidence_manifest(&manifest_path)?;
        if existing.state == EvidenceState::Complete {
            verify_manifest_files(request.result_dir, &existing)?;
            ensure_manifest_identity(&existing, &expanded.plan_sha256, &cell)?;
            return Ok(existing);
        }
        ensure!(
            request.resume,
            "partial result exists and resume is disabled"
        );
        quarantine_partial(request.result_dir)?;
    } else if request.result_dir.exists() {
        ensure!(
            request.resume,
            "partial result directory exists and resume is disabled"
        );
        quarantine_partial(request.result_dir)?;
    }

    let parent = request
        .result_dir
        .parent()
        .context("result directory has no parent")?;
    fs::create_dir_all(parent).with_context(|| format!("failed to create {}", parent.display()))?;
    let lock_path = parent.join(format!(".{}.lock", cell.id));
    fs::create_dir(&lock_path)
        .with_context(|| format!("cell lock already exists: {}", lock_path.display()))?;
    let _lock = CellLock { path: lock_path };
    fs::create_dir(request.result_dir)
        .with_context(|| format!("failed to create {}", request.result_dir.display()))?;

    let started_utc = now_utc();
    let mut manifest = EvidenceManifest {
        schema_version: crate::EVIDENCE_SCHEMA_VERSION,
        experiment_id: request.plan.experiment_id.clone(),
        plan_sha256: expanded.plan_sha256.clone(),
        cell_id: cell.id.clone(),
        cell: cell.clone(),
        state: EvidenceState::Running,
        started_utc,
        completed_utc: None,
        environment: capture_environment(&request, &cell)?,
        runs: Vec::new(),
        files: Vec::new(),
        bundle: None,
    };
    write_json_atomic(&manifest_path, &manifest)?;

    let result = run_cell_inner(&request, &cell, &mut manifest);
    manifest.completed_utc = Some(now_utc());
    match result {
        Ok(()) => {
            manifest.state = EvidenceState::Complete;
            manifest.files = collect_evidence_files(request.result_dir)?;
            verify_evidence_manifest(&manifest)?;
            write_json_atomic(&manifest_path, &manifest)?;
            verify_manifest_files(request.result_dir, &manifest)?;
            Ok(manifest)
        }
        Err(error) => {
            manifest.state = EvidenceState::Failed;
            manifest
                .environment
                .insert("failure".to_string(), format!("{error:#}"));
            manifest.files = collect_evidence_files(request.result_dir).unwrap_or_default();
            write_json_atomic(&manifest_path, &manifest)?;
            Err(error)
        }
    }
}

pub fn run_gradle_cell(request: RunGradleCellRequest<'_>) -> Result<EvidenceManifest> {
    let expanded = request.plan.expand()?;
    let cell = expanded
        .cells
        .iter()
        .find(|cell| cell.id == request.cell_id)
        .with_context(|| format!("plan has no cell {}", request.cell_id))?
        .clone();
    ensure!(
        cell.kind == CellKind::GradleTiming,
        "cell {} is not a Gradle timing cell",
        cell.id
    );
    preflight_gradle(&request, &cell)?;

    let manifest_path = request.result_dir.join("result.json");
    if manifest_path.is_file() {
        let existing = parse_evidence_manifest(&manifest_path)?;
        if existing.state == EvidenceState::Complete {
            verify_manifest_files(request.result_dir, &existing)?;
            ensure_manifest_identity(&existing, &expanded.plan_sha256, &cell)?;
            return Ok(existing);
        }
        ensure!(
            request.resume,
            "partial result exists and resume is disabled"
        );
        quarantine_partial(request.result_dir)?;
    } else if request.result_dir.exists() {
        ensure!(
            request.resume,
            "partial result directory exists and resume is disabled"
        );
        quarantine_partial(request.result_dir)?;
    }

    let parent = request
        .result_dir
        .parent()
        .context("result directory has no parent")?;
    fs::create_dir_all(parent)?;
    let lock_path = parent.join(format!(".{}.lock", cell.id));
    fs::create_dir(&lock_path)
        .with_context(|| format!("cell lock already exists: {}", lock_path.display()))?;
    let _lock = CellLock { path: lock_path };
    fs::create_dir(request.result_dir)?;

    let mut manifest = EvidenceManifest {
        schema_version: crate::EVIDENCE_SCHEMA_VERSION,
        experiment_id: request.plan.experiment_id.clone(),
        plan_sha256: expanded.plan_sha256.clone(),
        cell_id: cell.id.clone(),
        cell: cell.clone(),
        state: EvidenceState::Running,
        started_utc: now_utc(),
        completed_utc: None,
        environment: capture_gradle_environment(&request, &cell)?,
        runs: Vec::new(),
        files: Vec::new(),
        bundle: None,
    };
    write_json_atomic(&manifest_path, &manifest)?;

    let result = run_gradle_cell_inner(&request, &cell, &mut manifest);
    manifest.completed_utc = Some(now_utc());
    match result {
        Ok(()) => {
            manifest.state = EvidenceState::Complete;
            manifest.files = collect_evidence_files(request.result_dir)?;
            verify_evidence_manifest(&manifest)?;
            write_json_atomic(&manifest_path, &manifest)?;
            verify_manifest_files(request.result_dir, &manifest)?;
            Ok(manifest)
        }
        Err(error) => {
            manifest.state = EvidenceState::Failed;
            manifest
                .environment
                .insert("failure".to_string(), format!("{error:#}"));
            manifest.files = collect_evidence_files(request.result_dir).unwrap_or_default();
            write_json_atomic(&manifest_path, &manifest)?;
            Err(error)
        }
    }
}

fn preflight_gradle(request: &RunGradleCellRequest<'_>, cell: &ExpandedCell) -> Result<()> {
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
    ensure_file_hash(request.control_jar, &control.jar_sha256)?;
    ensure_file_hash(request.candidate_jar, &candidate.jar_sha256)?;
    ensure!(
        request.project_dir.is_dir(),
        "Gradle project does not exist"
    );
    ensure!(request.gradlew.is_file(), "Gradle wrapper does not exist");
    ensure!(request.java_bin.is_file(), "Java executable does not exist");
    verify_java_runtime(request.java_bin, cell)?;
    let expected_commit = cell
        .android_commit
        .as_deref()
        .context("Gradle cell has no Android commit")?;
    let head = command_text_in(request.project_dir, "git", &["rev-parse", "HEAD"]);
    ensure!(
        head == expected_commit,
        "Android checkout differs: expected {expected_commit}, actual {head}"
    );
    let status = command_text_in(
        request.project_dir,
        "git",
        &["status", "--porcelain", "--untracked-files=no"],
    );
    ensure!(
        status.is_empty(),
        "Android checkout has tracked modifications: {status}"
    );
    Ok(())
}

fn run_gradle_cell_inner(
    request: &RunGradleCellRequest<'_>,
    cell: &ExpandedCell,
    manifest: &mut EvidenceManifest,
) -> Result<()> {
    let injected_dir = request.project_dir.join(".r8-benchmark");
    fs::create_dir_all(&injected_dir)?;
    let injected_jar = injected_dir.join("r8.jar");
    for (index, variant) in variants_for_order(cell.order).iter().enumerate() {
        let ordinal = u16::try_from(index + 1).context("run ordinal overflow")?;
        let run_dir = request
            .result_dir
            .join(format!("run-{ordinal:02}-{}", variant_name(*variant)));
        fs::create_dir(&run_dir)?;
        write_host_snapshot(&run_dir.join("host-before.txt"))?;
        let source_jar = match variant {
            Variant::Control => request.control_jar,
            Variant::Candidate => request.candidate_jar,
        };
        fs::copy(source_jar, &injected_jar)?;
        let expected_jar_hash = sha256_file(source_jar)?;
        ensure_file_hash(&injected_jar, &expected_jar_hash)?;

        run_gradle_unmeasured(request, cell, &injected_jar, &["--stop"])?;
        let cleanup: Vec<_> = cell
            .gradle_cleanup_tasks
            .iter()
            .map(String::as_str)
            .collect();
        run_gradle_unmeasured(request, cell, &injected_jar, &cleanup)?;
        run_gradle_unmeasured(request, cell, &injected_jar, &["--stop"])?;

        let measured = run_gradle_measured(request, cell, &injected_jar, &run_dir)?;
        fs::write(run_dir.join("time.txt"), &measured.time_report)?;
        ensure!(
            measured.status.success(),
            "Gradle failed for run {ordinal} ({}) with status {}",
            variant_name(*variant),
            measured.status
        );
        let stdout_bytes = fs::read(run_dir.join("gradle-stdout.log"))?;
        let stdout = String::from_utf8_lossy(&stdout_bytes);
        let marker = format!(
            "R8_BENCHMARK_CLASSPATH_VERIFIED={}",
            injected_jar.canonicalize()?.display()
        );
        ensure!(
            stdout.contains(&marker),
            "Gradle did not verify injected R8: {marker}"
        );
        let outputs = collect_gradle_outputs(request.project_dir)?;
        write_json(&run_dir.join("output-hashes.json"), &outputs)?;
        write_host_snapshot(&run_dir.join("host-after.txt"))?;
        manifest.runs.push(BenchmarkRun {
            ordinal,
            variant: *variant,
            metrics: measured.metrics,
            outputs_sha256: outputs,
        });
        write_json_atomic(&request.result_dir.join("result.json"), manifest)?;
        if index + 1 < variants_for_order(cell.order).len()
            && request.plan.timing.cooldown_seconds > 0
        {
            thread::sleep(Duration::from_secs(u64::from(
                request.plan.timing.cooldown_seconds,
            )));
        }
    }
    let _ = run_gradle_unmeasured(request, cell, &injected_jar, &["--stop"]);
    Ok(())
}

struct MeasuredGradleRun {
    status: ExitStatus,
    time_report: Vec<u8>,
    metrics: RunMetrics,
}

fn run_gradle_unmeasured(
    request: &RunGradleCellRequest<'_>,
    cell: &ExpandedCell,
    injected_jar: &Path,
    requested_tasks: &[&str],
) -> Result<()> {
    let mut command = Command::new(request.gradlew);
    command.current_dir(request.project_dir);
    configure_gradle_command(&mut command, request, cell, injected_jar);
    if requested_tasks == ["--stop"] {
        command.arg("--stop");
    } else {
        command.args(requested_tasks).args(&cell.gradle_arguments);
    }
    let output = command
        .output()
        .context("failed to start Gradle preflight")?;
    ensure!(
        output.status.success(),
        "Gradle preflight failed with {}: {}",
        output.status,
        String::from_utf8_lossy(&output.stderr).trim()
    );
    Ok(())
}

fn run_gradle_measured(
    request: &RunGradleCellRequest<'_>,
    cell: &ExpandedCell,
    injected_jar: &Path,
    run_dir: &Path,
) -> Result<MeasuredGradleRun> {
    let time_path = run_dir.join("time.raw.txt");
    let mut command = Command::new("/usr/bin/time");
    if cfg!(target_os = "macos") {
        command.arg("-l");
    } else {
        command.arg("-v");
    }
    command
        .arg("-o")
        .arg(&time_path)
        .arg(request.gradlew)
        .arg("verifyR8BenchmarkClasspath")
        .args(&cell.gradle_tasks)
        .args(&cell.gradle_arguments)
        .current_dir(request.project_dir);
    configure_gradle_command(&mut command, request, cell, injected_jar);
    command
        .stdout(Stdio::from(File::create(
            run_dir.join("gradle-stdout.log"),
        )?))
        .stderr(Stdio::from(File::create(
            run_dir.join("gradle-stderr.log"),
        )?));

    let before_cpu = read_cgroup_cpu();
    let mut peak_memory = read_cgroup_value("/sys/fs/cgroup/memory.current");
    let started = Instant::now();
    let mut child = command.spawn().context("failed to start measured Gradle")?;
    loop {
        peak_memory = max_optional(
            peak_memory,
            read_cgroup_value("/sys/fs/cgroup/memory.current"),
        );
        if child.try_wait()?.is_some() {
            break;
        }
        thread::sleep(Duration::from_millis(250));
    }
    let status = child.wait()?;
    let elapsed = started.elapsed().as_secs_f64();
    let time_report =
        fs::read(&time_path).with_context(|| format!("failed to read {}", time_path.display()))?;
    let mut metrics = parse_time_report_text(&String::from_utf8_lossy(&time_report))?;
    metrics.wall_seconds = elapsed;
    if let (Some(before), Some(after)) = (before_cpu, read_cgroup_cpu()) {
        metrics.user_seconds = after
            .user_microseconds
            .saturating_sub(before.user_microseconds) as f64
            / 1e6;
        metrics.system_seconds = after
            .system_microseconds
            .saturating_sub(before.system_microseconds) as f64
            / 1e6;
    }
    if let Some(peak) = peak_memory {
        metrics.peak_footprint_bytes = peak;
    }
    Ok(MeasuredGradleRun {
        status,
        time_report,
        metrics,
    })
}

fn configure_gradle_command(
    command: &mut Command,
    request: &RunGradleCellRequest<'_>,
    cell: &ExpandedCell,
    injected_jar: &Path,
) {
    let gc = match cell.gc {
        GarbageCollector::G1 => "-XX:+UseG1GC",
        GarbageCollector::Parallel => "-XX:+UseParallelGC",
    };
    let jvm_args = format!(
        "-Xmx{} -Dfile.encoding=UTF-8 -Xss2M -Djava.awt.headless=true \
         -XX:+HeapDumpOnOutOfMemoryError -XX:ActiveProcessorCount={} {gc}",
        cell.xmx, cell.active_processors
    );
    let java_home = request
        .java_bin
        .parent()
        .and_then(Path::parent)
        .unwrap_or_else(|| Path::new(""));
    command
        .arg(format!("-Dr8.benchmark.jar={}", injected_jar.display()))
        .arg(format!("-Dorg.gradle.jvmargs={jvm_args}"))
        .env("JAVA_HOME", java_home)
        .env("R8_THREAD_COUNT", cell.r8_threads.to_string());
}

#[derive(Clone, Copy)]
struct CgroupCpu {
    user_microseconds: u64,
    system_microseconds: u64,
}

fn read_cgroup_cpu() -> Option<CgroupCpu> {
    let text = fs::read_to_string("/sys/fs/cgroup/cpu.stat").ok()?;
    let values: BTreeMap<_, _> = text
        .lines()
        .filter_map(|line| line.split_once(' '))
        .filter_map(|(key, value)| value.parse::<u64>().ok().map(|value| (key, value)))
        .collect();
    Some(CgroupCpu {
        user_microseconds: *values.get("user_usec")?,
        system_microseconds: *values.get("system_usec")?,
    })
}

fn read_cgroup_value(path: &str) -> Option<u64> {
    fs::read_to_string(path).ok()?.trim().parse().ok()
}

fn max_optional(left: Option<u64>, right: Option<u64>) -> Option<u64> {
    match (left, right) {
        (Some(left), Some(right)) => Some(left.max(right)),
        (left, right) => left.or(right),
    }
}

fn collect_gradle_outputs(project_dir: &Path) -> Result<BTreeMap<String, String>> {
    let mut outputs = BTreeMap::new();
    for entry in WalkDir::new(project_dir).follow_links(false) {
        let entry = entry?;
        if !entry.file_type().is_file() {
            continue;
        }
        let relative = entry.path().strip_prefix(project_dir)?;
        let relative_text = relative.to_string_lossy();
        let is_output = relative_text.contains("/build/outputs/")
            && matches!(
                entry.path().extension().and_then(|value| value.to_str()),
                Some("apk" | "aab" | "txt")
            );
        if is_output {
            outputs.insert(relative_text.to_string(), sha256_file(entry.path())?);
        }
    }
    ensure!(
        !outputs.is_empty(),
        "Gradle build produced no verifiable outputs"
    );
    Ok(outputs)
}

fn capture_gradle_environment(
    request: &RunGradleCellRequest<'_>,
    cell: &ExpandedCell,
) -> Result<BTreeMap<String, String>> {
    let mut environment = BTreeMap::from([
        ("host".to_string(), command_text("hostname", &[])),
        ("uname".to_string(), command_text("uname", &["-a"])),
        (
            "java_bin".to_string(),
            request.java_bin.display().to_string(),
        ),
        (
            "java_version".to_string(),
            command_text_path(request.java_bin, &["-version"]),
        ),
        (
            "requested_java_version".to_string(),
            cell.java_version.clone(),
        ),
        (
            "java_distribution".to_string(),
            cell.java_distribution.clone(),
        ),
        ("xmx".to_string(), cell.xmx.clone()),
        (
            "active_processors".to_string(),
            cell.active_processors.to_string(),
        ),
        ("r8_threads".to_string(), cell.r8_threads.to_string()),
        (
            "android_commit".to_string(),
            cell.android_commit.clone().unwrap_or_default(),
        ),
        (
            "gradle_cache_mode".to_string(),
            format!("{:?}", cell.gradle_cache_mode),
        ),
    ]);
    for path in [
        "/sys/fs/cgroup/cpu.max",
        "/sys/fs/cgroup/cpuset.cpus.effective",
        "/sys/fs/cgroup/memory.current",
        "/sys/fs/cgroup/memory.max",
        "/sys/fs/cgroup/memory.events",
    ] {
        environment.insert(
            format!("cgroup:{path}"),
            fs::read_to_string(path)
                .map(|value| value.trim().replace('\n', " "))
                .unwrap_or_else(|_| "unavailable".to_string()),
        );
    }
    Ok(environment)
}

fn command_text_in(directory: &Path, program: &str, arguments: &[&str]) -> String {
    Command::new(program)
        .args(arguments)
        .current_dir(directory)
        .output()
        .map(|output| String::from_utf8_lossy(&output.stdout).trim().to_string())
        .unwrap_or_else(|_| "unavailable".to_string())
}

fn preflight(request: &RunCellRequest<'_>, cell: &ExpandedCell) -> Result<()> {
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
    ensure_file_hash(request.control_jar, &control.jar_sha256)?;
    ensure_file_hash(request.candidate_jar, &candidate.jar_sha256)?;
    ensure_file_hash(request.input_archive, &request.plan.input.archive_sha256)?;
    ensure_file_hash(
        request.expected_outputs,
        &request.plan.input.expected_outputs_sha256,
    )?;
    ensure!(request.input_dir.is_dir(), "input directory does not exist");
    ensure!(request.runner.is_file(), "runner does not exist");
    ensure!(request.java_bin.is_file(), "Java executable does not exist");
    verify_java_runtime(request.java_bin, cell)?;
    parse_expected_outputs(request.expected_outputs)?;
    Ok(())
}

fn verify_java_runtime(java_bin: &Path, cell: &ExpandedCell) -> Result<()> {
    let version = command_text_path(java_bin, &["-version"]);
    ensure!(
        version.contains(&cell.java_version),
        "Java version differs: requested {}, actual {}",
        cell.java_version,
        version
    );
    let expected_vendor = match cell.java_distribution.to_ascii_lowercase().as_str() {
        "zulu" => Some("zulu"),
        "temurin" => Some("temurin"),
        "graalvm" | "graalvm-community" => Some("graalvm"),
        _ => None,
    };
    if let Some(expected_vendor) = expected_vendor {
        ensure!(
            version.to_ascii_lowercase().contains(expected_vendor),
            "Java distribution differs: requested {}, actual {}",
            cell.java_distribution,
            version
        );
    }
    Ok(())
}

fn run_cell_inner(
    request: &RunCellRequest<'_>,
    cell: &ExpandedCell,
    manifest: &mut EvidenceManifest,
) -> Result<()> {
    let order = variants_for_order(cell.order);
    for (index, variant) in order.iter().enumerate() {
        let ordinal = u16::try_from(index + 1).context("run ordinal overflow")?;
        let run_dir = request
            .result_dir
            .join(format!("run-{ordinal:02}-{}", variant_name(*variant)));
        fs::create_dir(&run_dir)
            .with_context(|| format!("failed to create {}", run_dir.display()))?;
        write_host_snapshot(&run_dir.join("host-before.txt"))?;
        let jar = match variant {
            Variant::Control => request.control_jar,
            Variant::Candidate => request.candidate_jar,
        };
        let mode = if cell.kind == CellKind::StandaloneDiagnostic {
            "diagnostic"
        } else {
            "no-jfr"
        };
        let output = invoke_runner(request, cell, jar, &run_dir, mode)?;
        fs::write(run_dir.join("runner-stdout.log"), &output.stdout)?;
        fs::write(run_dir.join("runner-stderr.log"), &output.stderr)?;
        ensure!(
            output.status.success(),
            "runner failed for run {ordinal} ({}) with status {}",
            variant_name(*variant),
            output.status
        );
        let outputs = verify_exact_outputs(request.expected_outputs, &run_dir)?;
        let metrics = parse_time_report(&run_dir.join("time.txt"))?;
        write_host_snapshot(&run_dir.join("host-after.txt"))?;
        manifest.runs.push(BenchmarkRun {
            ordinal,
            variant: *variant,
            metrics,
            outputs_sha256: outputs,
        });
        write_json_atomic(&request.result_dir.join("result.json"), manifest)?;
        if index + 1 < order.len() && request.plan.timing.cooldown_seconds > 0 {
            thread::sleep(Duration::from_secs(u64::from(
                request.plan.timing.cooldown_seconds,
            )));
        }
    }
    Ok(())
}

fn invoke_runner(
    request: &RunCellRequest<'_>,
    cell: &ExpandedCell,
    jar: &Path,
    run_dir: &Path,
    mode: &str,
) -> Result<Output> {
    let runtime = request
        .plan
        .runtimes
        .iter()
        .find(|runtime| runtime.id == cell.runtime_id)
        .context("missing runtime after plan validation")?;
    let mut command = Command::new(request.runner);
    command
        .arg(jar)
        .arg(request.input_dir)
        .arg(run_dir)
        .arg(mode)
        .env("JAVA_BIN", request.java_bin)
        .env("R8_JAVA_HEAP_SIZE", &runtime.xmx)
        .env(
            "R8_ACTIVE_PROCESSOR_COUNT",
            runtime.active_processors.to_string(),
        )
        .env("R8_THREAD_COUNT", runtime.r8_threads.to_string())
        .env(
            "R8_GC",
            match runtime.gc {
                GarbageCollector::G1 => "g1",
                GarbageCollector::Parallel => "parallel",
            },
        );
    if request.plan.diagnostics.enabled && !request.plan.diagnostics.jit_targets.is_empty() {
        command.env(
            "R8_JIT_TARGETS",
            request.plan.diagnostics.jit_targets.join(","),
        );
    }
    command
        .output()
        .with_context(|| format!("failed to start runner {}", request.runner.display()))
}

fn variants_for_order(order: PairOrder) -> &'static [Variant] {
    match order {
        PairOrder::AB => &[Variant::Control, Variant::Candidate],
        PairOrder::BA => &[Variant::Candidate, Variant::Control],
        PairOrder::ABBA => &[
            Variant::Control,
            Variant::Candidate,
            Variant::Candidate,
            Variant::Control,
        ],
    }
}

fn variant_name(variant: Variant) -> &'static str {
    match variant {
        Variant::Control => "control",
        Variant::Candidate => "candidate",
    }
}

fn parse_expected_outputs(path: &Path) -> Result<BTreeMap<String, String>> {
    let file = File::open(path).with_context(|| format!("failed to open {}", path.display()))?;
    let mut outputs = BTreeMap::new();
    for (line_number, line) in BufReader::new(file).lines().enumerate() {
        let line = line?;
        if line.trim().is_empty() || line.starts_with('#') {
            continue;
        }
        let columns: Vec<_> = line.split_whitespace().collect();
        ensure!(
            columns.len() == 2,
            "{}:{} must contain SHA256 and relative output path",
            path.display(),
            line_number + 1
        );
        let hash = columns[0];
        let relative = columns[1].trim_start_matches('*');
        ensure!(
            hash.len() == 64
                && hash
                    .bytes()
                    .all(|byte| byte.is_ascii_digit() || (b'a'..=b'f').contains(&byte)),
            "{}:{} has invalid SHA256",
            path.display(),
            line_number + 1
        );
        ensure!(
            is_safe_relative_path(relative),
            "unsafe output path {relative}"
        );
        ensure!(
            outputs
                .insert(relative.to_string(), hash.to_string())
                .is_none(),
            "duplicate expected output {relative}"
        );
    }
    ensure!(!outputs.is_empty(), "expected output manifest is empty");
    Ok(outputs)
}

fn verify_exact_outputs(expected_path: &Path, run_dir: &Path) -> Result<BTreeMap<String, String>> {
    let expected = parse_expected_outputs(expected_path)?;
    for (relative, hash) in &expected {
        ensure_file_hash(&run_dir.join(relative), hash)?;
    }
    Ok(expected)
}

pub fn parse_time_report(path: &Path) -> Result<RunMetrics> {
    let text = fs::read_to_string(path)
        .with_context(|| format!("failed to read timing report {}", path.display()))?;
    parse_time_report_text(&text)
}

fn parse_time_report_text(text: &str) -> Result<RunMetrics> {
    let mut wall_seconds = None;
    let mut user_seconds = None;
    let mut system_seconds = None;
    let mut max_rss_bytes = None;
    let mut peak_footprint_bytes = None;
    let mut instructions = None;
    let mut cycles = None;

    for line in text.lines() {
        let trimmed = line.trim();
        if let Some(value) = value_after_colon(trimmed, "User time (seconds):") {
            user_seconds = Some(parse_f64(value, "user time")?);
        } else if let Some(value) = value_after_colon(trimmed, "System time (seconds):") {
            system_seconds = Some(parse_f64(value, "system time")?);
        } else if let Some(value) =
            value_after_colon(trimmed, "Elapsed (wall clock) time (h:mm:ss or m:ss):")
        {
            wall_seconds = Some(parse_elapsed(value)?);
        } else if let Some(value) =
            value_after_colon(trimmed, "Maximum resident set size (kbytes):")
        {
            max_rss_bytes = Some(parse_u64(value, "maximum RSS")?.saturating_mul(1024));
        } else if let Some(value) = trimmed.strip_suffix(" maximum resident set size") {
            max_rss_bytes = Some(parse_u64(value.trim(), "maximum RSS")?);
        } else if let Some(value) = trimmed.strip_suffix(" peak memory footprint") {
            peak_footprint_bytes = Some(parse_u64(value.trim(), "peak footprint")?);
        } else if let Some(value) = trimmed.strip_suffix(" instructions retired") {
            instructions = Some(parse_u64(value.trim(), "instructions")?);
        } else if let Some(value) = trimmed.strip_suffix(" cycles elapsed") {
            cycles = Some(parse_u64(value.trim(), "cycles")?);
        } else if let Some(value) = value_after_colon(trimmed, "instructions:") {
            instructions = Some(parse_counter(value, "instructions")?);
        } else if let Some(value) = value_after_colon(trimmed, "cycles:") {
            cycles = Some(parse_counter(value, "cycles")?);
        } else {
            let columns: Vec<_> = trimmed.split_whitespace().collect();
            if columns.len() >= 6
                && columns[1] == "real"
                && columns[3] == "user"
                && columns[5] == "sys"
            {
                wall_seconds = Some(parse_f64(columns[0], "wall time")?);
                user_seconds = Some(parse_f64(columns[2], "user time")?);
                system_seconds = Some(parse_f64(columns[4], "system time")?);
            }
        }
    }
    let max_rss_bytes = max_rss_bytes.context("timing report has no maximum RSS")?;
    Ok(RunMetrics {
        wall_seconds: wall_seconds.context("timing report has no wall time")?,
        user_seconds: user_seconds.context("timing report has no user time")?,
        system_seconds: system_seconds.context("timing report has no system time")?,
        max_rss_bytes,
        peak_footprint_bytes: peak_footprint_bytes.unwrap_or(max_rss_bytes),
        instructions,
        cycles,
    })
}

fn value_after_colon<'a>(line: &'a str, prefix: &str) -> Option<&'a str> {
    line.strip_prefix(prefix).map(str::trim)
}

fn parse_f64(value: &str, name: &str) -> Result<f64> {
    let value: f64 = value
        .replace(',', "")
        .parse()
        .with_context(|| format!("invalid {name}: {value}"))?;
    ensure!(value.is_finite() && value >= 0.0, "invalid {name}: {value}");
    Ok(value)
}

fn parse_u64(value: &str, name: &str) -> Result<u64> {
    value
        .replace(',', "")
        .parse()
        .with_context(|| format!("invalid {name}: {value}"))
}

fn parse_counter(value: &str, name: &str) -> Result<u64> {
    parse_u64(value.split_whitespace().next().unwrap_or_default(), name)
}

fn parse_elapsed(value: &str) -> Result<f64> {
    let fields: Vec<_> = value.split(':').collect();
    match fields.as_slice() {
        [minutes, seconds] => {
            Ok(parse_f64(minutes, "elapsed minutes")? * 60.0
                + parse_f64(seconds, "elapsed seconds")?)
        }
        [hours, minutes, seconds] => Ok(parse_f64(hours, "elapsed hours")? * 3600.0
            + parse_f64(minutes, "elapsed minutes")? * 60.0
            + parse_f64(seconds, "elapsed seconds")?),
        _ => bail!("invalid elapsed time: {value}"),
    }
}

fn collect_evidence_files(root: &Path) -> Result<Vec<EvidenceFile>> {
    let mut files = Vec::new();
    for entry in WalkDir::new(root).follow_links(false) {
        let entry = entry?;
        if !entry.file_type().is_file() || entry.path() == root.join("result.json") {
            continue;
        }
        let relative = entry
            .path()
            .strip_prefix(root)?
            .to_string_lossy()
            .to_string();
        ensure!(
            is_safe_relative_path(&relative),
            "unsafe evidence path {relative}"
        );
        files.push(EvidenceFile {
            path: relative,
            sha256: sha256_file(entry.path())?,
            size_bytes: entry.metadata()?.len(),
        });
    }
    files.sort_by(|left, right| left.path.cmp(&right.path));
    Ok(files)
}

pub fn verify_manifest_files(root: &Path, manifest: &EvidenceManifest) -> Result<()> {
    verify_evidence_manifest(manifest)?;
    let mut listed = BTreeSet::new();
    for file in &manifest.files {
        ensure!(listed.insert(file.path.as_str()), "duplicate evidence path");
        let path = root.join(&file.path);
        ensure!(path.is_file(), "missing evidence file {}", path.display());
        ensure!(
            path.metadata()?.len() == file.size_bytes,
            "evidence size mismatch for {}",
            path.display()
        );
        ensure_file_hash(&path, &file.sha256)?;
    }
    let actual: BTreeSet<_> = collect_evidence_files(root)?
        .into_iter()
        .map(|file| file.path)
        .collect();
    let expected: BTreeSet<_> = manifest
        .files
        .iter()
        .map(|file| file.path.clone())
        .collect();
    ensure!(
        actual == expected,
        "evidence file set differs from manifest"
    );
    Ok(())
}

fn ensure_manifest_identity(
    manifest: &EvidenceManifest,
    plan_sha256: &str,
    cell: &ExpandedCell,
) -> Result<()> {
    ensure!(
        manifest.plan_sha256 == plan_sha256,
        "plan fingerprint changed"
    );
    ensure!(manifest.cell == *cell, "expanded cell changed");
    Ok(())
}

fn capture_environment(
    request: &RunCellRequest<'_>,
    cell: &ExpandedCell,
) -> Result<BTreeMap<String, String>> {
    let runtime = request
        .plan
        .runtimes
        .iter()
        .find(|runtime| runtime.id == cell.runtime_id)
        .context("missing runtime after plan validation")?;
    let mut environment = BTreeMap::from([
        ("host".to_string(), command_text("hostname", &[])),
        ("uname".to_string(), command_text("uname", &["-a"])),
        (
            "host_available_parallelism".to_string(),
            std::thread::available_parallelism()
                .map(|value| value.get().to_string())
                .unwrap_or_else(|_| "unknown".to_string()),
        ),
        (
            "java_bin".to_string(),
            request.java_bin.display().to_string(),
        ),
        (
            "java_sha256".to_string(),
            sha256_file(request.java_bin).unwrap_or_else(|_| "unavailable".to_string()),
        ),
        (
            "java_version".to_string(),
            command_text_path(request.java_bin, &["-version"]),
        ),
        (
            "java_distribution".to_string(),
            runtime.java_distribution.clone(),
        ),
        (
            "requested_java_version".to_string(),
            runtime.java_version.clone(),
        ),
        ("xmx".to_string(), runtime.xmx.clone()),
        (
            "active_processors".to_string(),
            runtime.active_processors.to_string(),
        ),
        ("r8_threads".to_string(), runtime.r8_threads.to_string()),
        (
            "input_archive_sha256".to_string(),
            request.plan.input.archive_sha256.clone(),
        ),
        (
            "expected_outputs_sha256".to_string(),
            request.plan.input.expected_outputs_sha256.clone(),
        ),
    ]);
    for path in [
        "/sys/fs/cgroup/cpu.max",
        "/sys/fs/cgroup/cpuset.cpus.effective",
        "/sys/fs/cgroup/memory.current",
        "/sys/fs/cgroup/memory.max",
        "/sys/fs/cgroup/memory.events",
    ] {
        let value = fs::read_to_string(path)
            .map(|value| value.trim().replace('\n', " "))
            .unwrap_or_else(|_| "unavailable".to_string());
        environment.insert(format!("cgroup:{path}"), value);
    }
    Ok(environment)
}

fn write_host_snapshot(path: &Path) -> Result<()> {
    let mut text = String::new();
    text.push_str(&format!("utc\t{}\n", now_utc()));
    text.push_str(&format!("uptime\t{}\n", command_text("uptime", &[])));
    text.push_str(&format!("vm_stat\t{}\n", command_text("vm_stat", &[])));
    text.push_str(&format!(
        "macos_swap\t{}\n",
        command_text("sysctl", &["vm.swapusage"])
    ));
    text.push_str(&format!(
        "macos_thermal\t{}\n",
        command_text("pmset", &["-g", "therm"])
    ));
    text.push_str(&format!(
        "processes\t{}\n",
        command_text("ps", &["-axo", "pid=,pcpu=,rss=,command="])
    ));
    for system_path in [
        "/proc/meminfo",
        "/proc/pressure/cpu",
        "/proc/pressure/memory",
        "/proc/pressure/io",
    ] {
        if let Ok(value) = fs::read_to_string(system_path) {
            text.push_str(&format!(
                "{system_path}\t{}\n",
                value.trim().replace('\n', " ")
            ));
        }
    }
    for cgroup in [
        "/sys/fs/cgroup/cpu.max",
        "/sys/fs/cgroup/cpuset.cpus.effective",
        "/sys/fs/cgroup/memory.current",
        "/sys/fs/cgroup/memory.max",
        "/sys/fs/cgroup/memory.events",
    ] {
        if let Ok(value) = fs::read_to_string(cgroup) {
            text.push_str(&format!("{cgroup}\t{}\n", value.trim().replace('\n', " ")));
        }
    }
    text.push_str(&thermal_snapshot());
    fs::write(path, text).with_context(|| format!("failed to write {}", path.display()))
}

fn thermal_snapshot() -> String {
    let mut output = String::new();
    for root in ["/sys/class/thermal", "/sys/class/hwmon"] {
        let Ok(entries) = fs::read_dir(root) else {
            continue;
        };
        for entry in entries.flatten() {
            let Ok(files) = fs::read_dir(entry.path()) else {
                continue;
            };
            for file in files.flatten() {
                let name = file.file_name();
                let name = name.to_string_lossy();
                if !(name == "temp" || name.starts_with("temp") && name.ends_with("_input")) {
                    continue;
                }
                if let Ok(value) = fs::read_to_string(file.path()) {
                    output.push_str(&format!(
                        "thermal:{}\t{}\n",
                        file.path().display(),
                        value.trim()
                    ));
                }
            }
        }
    }
    output
}

fn command_text(program: &str, arguments: &[&str]) -> String {
    command_text_path(Path::new(program), arguments)
}

fn command_text_path(program: &Path, arguments: &[&str]) -> String {
    Command::new(program)
        .args(arguments)
        .output()
        .map(|output| {
            let mut text = String::from_utf8_lossy(&output.stdout).trim().to_string();
            let stderr = String::from_utf8_lossy(&output.stderr);
            if !stderr.trim().is_empty() {
                if !text.is_empty() {
                    text.push(' ');
                }
                text.push_str(stderr.trim());
            }
            text.replace('\n', " ")
        })
        .unwrap_or_else(|_| "unavailable".to_string())
}

fn write_json_atomic(path: &Path, value: &impl serde::Serialize) -> Result<()> {
    let temporary = path.with_extension("json.tmp");
    write_json(&temporary, value)?;
    fs::rename(&temporary, path)
        .with_context(|| format!("failed to atomically replace {}", path.display()))
}

fn quarantine_partial(result_dir: &Path) -> Result<()> {
    let parent = result_dir
        .parent()
        .context("result directory has no parent")?;
    let quarantine = parent.join(".incomplete");
    fs::create_dir_all(&quarantine)?;
    let name = result_dir
        .file_name()
        .context("result directory has no name")?
        .to_string_lossy();
    let destination = quarantine.join(format!("{name}-{}", Utc::now().format("%Y%m%dT%H%M%SZ")));
    fs::rename(result_dir, &destination).with_context(|| {
        format!(
            "failed to preserve partial result {} as {}",
            result_dir.display(),
            destination.display()
        )
    })
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

fn now_utc() -> String {
    Utc::now().to_rfc3339_opts(SecondsFormat::Secs, true)
}

#[cfg(test)]
mod tests {
    use std::collections::BTreeMap;
    use std::io::Write;
    use std::os::unix::fs::PermissionsExt;

    use tempfile::tempdir;

    use super::*;
    use crate::{
        Comparison, DiagnosticsSpec, GarbageCollector, GradleSpec, InputArtifact, R8Artifact,
        RuntimeSpec, StorageSpec, TimingSpec,
    };

    #[test]
    fn parses_gnu_time() {
        let metrics = parse_time_report_text(
            "User time (seconds): 10.25\nSystem time (seconds): 1.5\nElapsed (wall clock) time (h:mm:ss or m:ss): 1:02.50\nMaximum resident set size (kbytes): 2048\n",
        )
        .unwrap();
        assert_eq!(metrics.wall_seconds, 62.5);
        assert_eq!(metrics.user_seconds, 10.25);
        assert_eq!(metrics.system_seconds, 1.5);
        assert_eq!(metrics.max_rss_bytes, 2 * 1024 * 1024);
        assert_eq!(metrics.peak_footprint_bytes, metrics.max_rss_bytes);
    }

    #[test]
    fn parses_bsd_time() {
        let metrics = parse_time_report_text(
            "1.25 real 4.50 user 0.25 sys\n 123456 maximum resident set size\n 234567 peak memory footprint\n 987654 instructions retired\n 456789 cycles elapsed\n",
        )
        .unwrap();
        assert_eq!(metrics.wall_seconds, 1.25);
        assert_eq!(metrics.max_rss_bytes, 123456);
        assert_eq!(metrics.peak_footprint_bytes, 234567);
        assert_eq!(metrics.instructions, Some(987654));
        assert_eq!(metrics.cycles, Some(456789));
    }

    #[test]
    fn rejects_incomplete_time_report() {
        assert!(parse_time_report_text("1.0 real\n").is_err());
    }

    #[test]
    fn standalone_runner_expands_private_argument_placeholders() {
        let root = tempdir().unwrap();
        let input = root.path().join("input");
        let output = root.path().join("output");
        fs::create_dir(&input).unwrap();
        fs::write(
            input.join("r8-arguments.txt"),
            "--output\n{output_dir}/base.zip\n{input_dir}/program.jar\n",
        )
        .unwrap();
        let capture = root.path().join("java-arguments.txt");
        let java = root.path().join("fake-java");
        fs::write(
            &java,
            "#!/bin/sh\nprintf '%s\\n' \"$@\" > \"$CAPTURE_FILE\"\n",
        )
        .unwrap();
        fs::set_permissions(&java, fs::Permissions::from_mode(0o755)).unwrap();
        let runner = Path::new(env!("CARGO_MANIFEST_DIR"))
            .parent()
            .unwrap()
            .join("run_standalone_r8_benchmark.sh");

        let status = Command::new("bash")
            .arg(runner)
            .arg(root.path().join("r8.jar"))
            .arg(&input)
            .arg(&output)
            .arg("no-jfr")
            .env("JAVA_BIN", &java)
            .env("CAPTURE_FILE", &capture)
            .status()
            .unwrap();
        assert!(status.success());
        let captured = fs::read_to_string(capture).unwrap();
        assert!(captured.contains(&format!("{}/program.jar", input.display())));
        assert!(captured.contains(&format!("{}/base.zip", output.display())));
        assert!(captured.contains("--thread-count\n10\n"));
    }

    #[test]
    fn runs_and_reverifies_a_fake_paired_cell() {
        let root = tempdir().unwrap();
        let control_jar = root.path().join("control.jar");
        let candidate_jar = root.path().join("candidate.jar");
        let input_archive = root.path().join("input.tar.zst");
        fs::write(&control_jar, b"control").unwrap();
        fs::write(&candidate_jar, b"candidate").unwrap();
        fs::write(&input_archive, b"input archive").unwrap();

        let input_dir = root.path().join("input");
        fs::create_dir(&input_dir).unwrap();
        let expected_files = [
            ("base.zip", "expected-base.zip", b"base".as_slice()),
            (
                "feature-1.zip",
                "expected-feature-1.zip",
                b"feature".as_slice(),
            ),
            ("mapping.txt", "expected-mapping.txt", b"mapping".as_slice()),
            (
                "art-profile-1.txt",
                "expected-art-profile-1.txt",
                b"profile".as_slice(),
            ),
        ];
        let expected_outputs = root.path().join("expected-outputs.tsv");
        let mut expected_writer = File::create(&expected_outputs).unwrap();
        for (output_name, input_name, contents) in expected_files {
            let input_path = input_dir.join(input_name);
            fs::write(&input_path, contents).unwrap();
            writeln!(
                expected_writer,
                "{}  {output_name}",
                sha256_file(&input_path).unwrap()
            )
            .unwrap();
        }
        drop(expected_writer);

        let plan = ExperimentPlan {
            schema_version: crate::PLAN_SCHEMA_VERSION,
            experiment_id: "fake-cell".to_string(),
            storage: StorageSpec {
                root_uri: "gs://bucket/r8-benchmark".to_string(),
            },
            max_parallel: 1,
            artifacts: BTreeMap::from([
                (
                    "control".to_string(),
                    R8Artifact {
                        repository: "example/r8".to_string(),
                        commit: "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa".to_string(),
                        jar_sha256: sha256_file(&control_jar).unwrap(),
                    },
                ),
                (
                    "candidate".to_string(),
                    R8Artifact {
                        repository: "example/r8".to_string(),
                        commit: "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb".to_string(),
                        jar_sha256: sha256_file(&candidate_jar).unwrap(),
                    },
                ),
            ]),
            input: InputArtifact {
                archive_sha256: sha256_file(&input_archive).unwrap(),
                expected_outputs_sha256: sha256_file(&expected_outputs).unwrap(),
            },
            comparisons: vec![Comparison {
                id: "candidate".to_string(),
                control: "control".to_string(),
                candidate: "candidate".to_string(),
            }],
            runtimes: vec![RuntimeSpec {
                id: "test".to_string(),
                java_distribution: "test".to_string(),
                java_version: "21".to_string(),
                gc: GarbageCollector::G1,
                xmx: "1g".to_string(),
                active_processors: 1,
                r8_threads: 1,
                runner_labels: vec!["test".to_string()],
            }],
            timing: TimingSpec {
                pairs: 1,
                cooldown_seconds: 0,
            },
            diagnostics: DiagnosticsSpec::default(),
            gradle: GradleSpec::default(),
        };
        let cell_id = plan.expand().unwrap().cells[0].id.clone();
        let runner =
            Path::new(env!("CARGO_MANIFEST_DIR")).join("testdata/fake_standalone_runner.sh");
        let java_bin = root.path().join("fake-java");
        fs::write(&java_bin, "#!/bin/sh\necho 'openjdk version 21 test' >&2\n").unwrap();
        fs::set_permissions(&java_bin, fs::Permissions::from_mode(0o755)).unwrap();
        let result_dir = root.path().join(&cell_id);
        let request = || RunCellRequest {
            plan: &plan,
            cell_id: &cell_id,
            control_jar: &control_jar,
            candidate_jar: &candidate_jar,
            input_archive: &input_archive,
            input_dir: &input_dir,
            expected_outputs: &expected_outputs,
            runner: &runner,
            java_bin: &java_bin,
            result_dir: &result_dir,
            resume: true,
        };

        let manifest = run_cell(request()).unwrap();
        assert_eq!(manifest.state, EvidenceState::Complete);
        assert_eq!(manifest.runs.len(), 2);
        assert!(manifest.files.len() >= 20);
        assert_eq!(run_cell(request()).unwrap(), manifest);
        let report = crate::report::aggregate_local(&plan, root.path(), true).unwrap();
        assert_eq!(report.complete_cells, 1);
        assert_eq!(report.groups.len(), 1);
        assert_eq!(report.groups[0].wall_percent.mean, 0.0);

        let bundle = root.path().join("evidence.tar.zst");
        crate::external::create_evidence_bundle(&result_dir, &manifest, &bundle).unwrap();
        let mut bundled_manifest = manifest.clone();
        bundled_manifest.bundle = Some(EvidenceFile {
            path: "evidence.tar.zst".to_string(),
            sha256: sha256_file(&bundle).unwrap(),
            size_bytes: bundle.metadata().unwrap().len(),
        });
        write_json(&result_dir.join("result.json"), &bundled_manifest).unwrap();
        crate::external::verify_evidence_bundle(
            &result_dir.join("result.json"),
            &bundle,
            &root.path().join("extracted"),
        )
        .unwrap();

        fs::write(result_dir.join("run-01-control/base.zip"), b"tampered").unwrap();
        assert!(run_cell(request()).is_err());
        let report = crate::report::aggregate_local(&plan, root.path(), true).unwrap();
        assert_eq!(report.invalid_cells, 1);
    }
}
