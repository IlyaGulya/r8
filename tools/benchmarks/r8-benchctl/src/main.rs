// Copyright (c) 2026, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

use std::fs::{self, OpenOptions};
use std::io::{self, Write};
use std::path::{Path, PathBuf};

use anyhow::{Context, Result};
use clap::{Args, Parser, Subcommand, ValueEnum};
use r8_benchctl::cell::{RunCellRequest, RunGradleCellRequest, run_cell, run_gradle_cell};
use r8_benchctl::external::{
    CollectRequest, PrepareCellRequest, PublishArtifactRequest, PublishInputRequest, SubmitRequest,
    UploadEvidenceRequest, collect_manifests, prepare_cell, publish_artifact, publish_input,
    submit, upload_evidence, verify_evidence_bundle,
};
use r8_benchctl::report::{aggregate_local, markdown};
use r8_benchctl::{
    ExperimentPlan, GitHubMatrix, ensure_file_hash, evidence_manifest_schema,
    experiment_plan_schema, github_strategy_cells, parse_evidence_manifest, write_json,
};

#[derive(Parser)]
#[command(name = "r8-benchctl")]
#[command(about = "Plan, verify, and orchestrate paired R8 benchmark matrices")]
struct Cli {
    #[command(subcommand)]
    command: Command,
}

#[derive(Subcommand)]
enum Command {
    Plan(PlanArgs),
    Artifact(ArtifactArgs),
    Input(InputArgs),
    Cell(CellArgs),
    Matrix(MatrixArgs),
    Submit(SubmitArgs),
    Schema(SchemaArgs),
    Evidence(EvidenceArgs),
    Hash(HashArgs),
}

#[derive(Args)]
struct ArtifactArgs {
    #[command(subcommand)]
    command: ArtifactCommand,
}

#[derive(Subcommand)]
enum ArtifactCommand {
    Resolve {
        #[arg(long)]
        plan: PathBuf,
        #[arg(long)]
        artifact_id: String,
    },
    Publish {
        #[arg(long)]
        plan: PathBuf,
        #[arg(long)]
        artifact_id: String,
        #[arg(long)]
        jar: PathBuf,
        #[arg(long, default_value = "gcloud")]
        gcloud: PathBuf,
        #[arg(long)]
        dry_run: bool,
    },
}

#[derive(Args)]
struct InputArgs {
    #[command(subcommand)]
    command: InputCommand,
}

#[derive(Subcommand)]
enum InputCommand {
    Publish {
        #[arg(long)]
        plan: PathBuf,
        #[arg(long)]
        archive: PathBuf,
        #[arg(long)]
        expected_outputs: PathBuf,
        #[arg(long, default_value = "gcloud")]
        gcloud: PathBuf,
        #[arg(long)]
        dry_run: bool,
    },
}

#[derive(Args)]
struct SubmitArgs {
    #[arg(long)]
    plan: PathBuf,
    #[arg(long)]
    repository: String,
    #[arg(long, default_value = "r8_benchmark_matrix.yml")]
    workflow: String,
    #[arg(long, default_value = "dev")]
    workflow_ref: String,
    #[arg(long)]
    tooling_ref: String,
    #[arg(long, default_value = "gcloud")]
    gcloud: PathBuf,
    #[arg(long, default_value = "gh")]
    gh: PathBuf,
    #[arg(long)]
    dry_run: bool,
}

#[derive(Args)]
struct MatrixArgs {
    #[command(subcommand)]
    command: MatrixCommand,
}

#[derive(Subcommand)]
enum MatrixCommand {
    Status {
        #[arg(long)]
        plan: PathBuf,
        #[arg(long)]
        result_root: PathBuf,
    },
    Verify {
        #[arg(long)]
        plan: PathBuf,
        #[arg(long)]
        result_root: PathBuf,
        #[arg(long)]
        allow_partial: bool,
    },
    Report {
        #[arg(long)]
        plan: PathBuf,
        #[arg(long)]
        result_root: PathBuf,
        #[arg(long, value_enum, default_value_t = ReportFormat::Markdown)]
        format: ReportFormat,
        #[arg(long)]
        output: Option<PathBuf>,
    },
    Collect {
        #[arg(long)]
        plan: PathBuf,
        #[arg(long)]
        output_dir: PathBuf,
        #[arg(long, default_value = "gcloud")]
        gcloud: PathBuf,
    },
}

#[derive(Clone, Copy, ValueEnum)]
enum ReportFormat {
    Json,
    Markdown,
}

#[derive(Args)]
struct CellArgs {
    #[command(subcommand)]
    command: CellCommand,
}

#[derive(Subcommand)]
enum CellCommand {
    Prepare {
        #[arg(long)]
        plan: PathBuf,
        #[arg(long)]
        cell_id: String,
        #[arg(long)]
        output_dir: PathBuf,
        #[arg(long, default_value = "gcloud")]
        gcloud: PathBuf,
        #[arg(long, default_value = "tar")]
        tar: PathBuf,
    },
    Run {
        #[arg(long)]
        plan: PathBuf,
        #[arg(long)]
        cell_id: String,
        #[arg(long)]
        control_jar: PathBuf,
        #[arg(long)]
        candidate_jar: PathBuf,
        #[arg(long)]
        input_archive: PathBuf,
        #[arg(long)]
        input_dir: PathBuf,
        #[arg(long)]
        expected_outputs: PathBuf,
        #[arg(long)]
        runner: PathBuf,
        #[arg(long)]
        java_bin: PathBuf,
        #[arg(long)]
        result_dir: PathBuf,
        #[arg(long, default_value_t = true)]
        resume: bool,
    },
    RunGradle {
        #[arg(long)]
        plan: PathBuf,
        #[arg(long)]
        cell_id: String,
        #[arg(long)]
        control_jar: PathBuf,
        #[arg(long)]
        candidate_jar: PathBuf,
        #[arg(long)]
        project_dir: PathBuf,
        #[arg(long)]
        gradlew: PathBuf,
        #[arg(long)]
        java_bin: PathBuf,
        #[arg(long)]
        result_dir: PathBuf,
        #[arg(long, default_value_t = true)]
        resume: bool,
    },
}

#[derive(Args)]
struct PlanArgs {
    #[command(subcommand)]
    command: PlanCommand,
}

#[derive(Subcommand)]
enum PlanCommand {
    Validate {
        plan: PathBuf,
    },
    Fingerprint {
        plan: PathBuf,
    },
    Expand {
        plan: PathBuf,
        #[arg(long)]
        github_matrix: bool,
        #[arg(long)]
        output: Option<PathBuf>,
    },
    Paths {
        plan: PathBuf,
    },
    GithubOutputs {
        plan: PathBuf,
        #[arg(long)]
        output: PathBuf,
    },
}

#[derive(Args)]
struct SchemaArgs {
    #[command(subcommand)]
    command: SchemaCommand,
}

#[derive(Subcommand)]
enum SchemaCommand {
    Plan { output: Option<PathBuf> },
    Evidence { output: Option<PathBuf> },
}

#[derive(Args)]
struct EvidenceArgs {
    #[command(subcommand)]
    command: EvidenceCommand,
}

#[derive(Subcommand)]
enum EvidenceCommand {
    Verify {
        manifest: PathBuf,
    },
    Upload {
        #[arg(long)]
        plan: PathBuf,
        #[arg(long)]
        cell_id: String,
        #[arg(long)]
        result_dir: PathBuf,
        #[arg(long, default_value = "gcloud")]
        gcloud: PathBuf,
        #[arg(long)]
        dry_run: bool,
    },
    VerifyBundle {
        #[arg(long)]
        manifest: PathBuf,
        #[arg(long)]
        bundle: PathBuf,
        #[arg(long)]
        extraction_root: PathBuf,
    },
}

#[derive(Args)]
struct HashArgs {
    path: PathBuf,
    #[arg(long)]
    expect: Option<String>,
}

fn main() {
    if let Err(error) = run() {
        eprintln!("error: {error:#}");
        std::process::exit(1);
    }
}

fn run() -> Result<()> {
    match Cli::parse().command {
        Command::Plan(args) => run_plan(args.command),
        Command::Artifact(args) => run_artifact(args.command),
        Command::Input(args) => run_input(args.command),
        Command::Cell(args) => run_cell_command(args.command),
        Command::Matrix(args) => run_matrix(args.command),
        Command::Submit(args) => run_submit(args),
        Command::Schema(args) => run_schema(args.command),
        Command::Evidence(args) => run_evidence(args.command),
        Command::Hash(args) => run_hash(args),
    }
}

fn run_artifact(command: ArtifactCommand) -> Result<()> {
    match command {
        ArtifactCommand::Resolve { plan, artifact_id } => {
            let plan = ExperimentPlan::from_toml_file(&plan)?;
            let artifact = plan
                .artifacts
                .get(&artifact_id)
                .with_context(|| format!("unknown artifact {artifact_id}"))?;
            print_json(&serde_json::json!({
                "artifact_id": &artifact_id,
                "jar_uri": plan.artifact_uri(&artifact_id)?,
                "manifest_uri": plan.artifact_manifest_uri(&artifact_id)?,
                "jar_sha256": artifact.jar_sha256,
                "repository": artifact.repository,
                "commit": artifact.commit,
            }))
        }
        ArtifactCommand::Publish {
            plan,
            artifact_id,
            jar,
            gcloud,
            dry_run,
        } => {
            let plan = ExperimentPlan::from_toml_file(&plan)?;
            print_json(&publish_artifact(PublishArtifactRequest {
                plan: &plan,
                artifact_id: &artifact_id,
                jar: &jar,
                gcloud: &gcloud,
                dry_run,
            })?)
        }
    }
}

fn run_input(command: InputCommand) -> Result<()> {
    match command {
        InputCommand::Publish {
            plan,
            archive,
            expected_outputs,
            gcloud,
            dry_run,
        } => {
            let plan = ExperimentPlan::from_toml_file(&plan)?;
            print_json(&publish_input(PublishInputRequest {
                plan: &plan,
                archive: &archive,
                expected_outputs: &expected_outputs,
                gcloud: &gcloud,
                dry_run,
            })?)
        }
    }
}

fn run_submit(args: SubmitArgs) -> Result<()> {
    let plan = ExperimentPlan::from_toml_file(&args.plan)?;
    print_json(&submit(SubmitRequest {
        plan: &plan,
        plan_path: &args.plan,
        repository: &args.repository,
        workflow: &args.workflow,
        workflow_ref: &args.workflow_ref,
        tooling_ref: &args.tooling_ref,
        gcloud: &args.gcloud,
        gh: &args.gh,
        dry_run: args.dry_run,
    })?)
}

fn run_matrix(command: MatrixCommand) -> Result<()> {
    match command {
        MatrixCommand::Status { plan, result_root } => {
            let report =
                aggregate_local(&ExperimentPlan::from_toml_file(&plan)?, &result_root, false)?;
            print_json(&report)
        }
        MatrixCommand::Verify {
            plan,
            result_root,
            allow_partial,
        } => {
            let report =
                aggregate_local(&ExperimentPlan::from_toml_file(&plan)?, &result_root, true)?;
            anyhow::ensure!(
                report.failed_cells == 0 && report.invalid_cells == 0,
                "matrix contains {} failed and {} invalid cells",
                report.failed_cells,
                report.invalid_cells
            );
            if !allow_partial {
                anyhow::ensure!(
                    report.complete_cells == report.expected_cells,
                    "matrix is partial: {} of {} cells complete",
                    report.complete_cells,
                    report.expected_cells
                );
            }
            print_json(&serde_json::json!({
                "valid": true,
                "partial": report.complete_cells != report.expected_cells,
                "complete_cells": report.complete_cells,
                "expected_cells": report.expected_cells,
                "plan_sha256": report.plan_sha256,
            }))
        }
        MatrixCommand::Report {
            plan,
            result_root,
            format,
            output,
        } => {
            let report =
                aggregate_local(&ExperimentPlan::from_toml_file(&plan)?, &result_root, false)?;
            match format {
                ReportFormat::Json => {
                    if let Some(path) = output {
                        write_json(&path, &report)
                    } else {
                        print_json(&report)
                    }
                }
                ReportFormat::Markdown => {
                    let markdown = markdown(&report)?;
                    if let Some(path) = output {
                        if let Some(parent) = path.parent() {
                            fs::create_dir_all(parent)?;
                        }
                        fs::write(&path, markdown)
                            .with_context(|| format!("failed to write {}", path.display()))
                    } else {
                        print!("{markdown}");
                        Ok(())
                    }
                }
            }
        }
        MatrixCommand::Collect {
            plan,
            output_dir,
            gcloud,
        } => {
            let plan = ExperimentPlan::from_toml_file(&plan)?;
            let collected = collect_manifests(CollectRequest {
                plan: &plan,
                output_dir: &output_dir,
                gcloud: &gcloud,
            })?;
            let report = aggregate_local(&plan, &output_dir, false)?;
            print_json(&serde_json::json!({
                "collected": collected,
                "complete": report.complete_cells,
                "running": report.running_cells,
                "failed": report.failed_cells,
                "invalid": report.invalid_cells,
                "missing": report.missing_cells,
                "expected": report.expected_cells,
            }))
        }
    }
}

fn run_cell_command(command: CellCommand) -> Result<()> {
    match command {
        CellCommand::Prepare {
            plan,
            cell_id,
            output_dir,
            gcloud,
            tar,
        } => {
            let plan = ExperimentPlan::from_toml_file(&plan)?;
            print_json(&prepare_cell(PrepareCellRequest {
                plan: &plan,
                cell_id: &cell_id,
                output_dir: &output_dir,
                gcloud: &gcloud,
                tar: &tar,
            })?)
        }
        CellCommand::Run {
            plan,
            cell_id,
            control_jar,
            candidate_jar,
            input_archive,
            input_dir,
            expected_outputs,
            runner,
            java_bin,
            result_dir,
            resume,
        } => {
            let plan = ExperimentPlan::from_toml_file(&plan)?;
            let manifest = run_cell(RunCellRequest {
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
                resume,
            })?;
            print_json(&manifest)
        }
        CellCommand::RunGradle {
            plan,
            cell_id,
            control_jar,
            candidate_jar,
            project_dir,
            gradlew,
            java_bin,
            result_dir,
            resume,
        } => {
            let plan = ExperimentPlan::from_toml_file(&plan)?;
            let manifest = run_gradle_cell(RunGradleCellRequest {
                plan: &plan,
                cell_id: &cell_id,
                control_jar: &control_jar,
                candidate_jar: &candidate_jar,
                project_dir: &project_dir,
                gradlew: &gradlew,
                java_bin: &java_bin,
                result_dir: &result_dir,
                resume,
            })?;
            print_json(&manifest)
        }
    }
}

fn run_plan(command: PlanCommand) -> Result<()> {
    match command {
        PlanCommand::Validate { plan } => {
            let plan = ExperimentPlan::from_toml_file(&plan)?;
            let expanded = plan.expand()?;
            print_json(&serde_json::json!({
                "valid": true,
                "experiment_id": plan.experiment_id,
                "plan_sha256": expanded.plan_sha256,
                "cell_count": expanded.cells.len(),
                "max_parallel": expanded.max_parallel,
            }))
        }
        PlanCommand::Fingerprint { plan } => {
            println!("{}", ExperimentPlan::from_toml_file(&plan)?.fingerprint()?);
            Ok(())
        }
        PlanCommand::Expand {
            plan,
            github_matrix,
            output,
        } => {
            let expanded = ExperimentPlan::from_toml_file(&plan)?.expand()?;
            if let Some(path) = output {
                if github_matrix {
                    write_json(
                        &path,
                        &GitHubMatrix {
                            include: &expanded.cells,
                        },
                    )
                } else {
                    write_json(&path, &expanded)
                }
            } else if github_matrix {
                print_json(&GitHubMatrix {
                    include: &expanded.cells,
                })
            } else {
                print_json(&expanded)
            }
        }
        PlanCommand::Paths { plan } => {
            let plan = ExperimentPlan::from_toml_file(&plan)?;
            let expanded = plan.expand()?;
            let artifacts = plan
                .artifacts
                .keys()
                .map(|id| Ok((id.clone(), plan.artifact_uri(id)?)))
                .collect::<Result<std::collections::BTreeMap<_, _>>>()?;
            print_json(&serde_json::json!({
                "plan_sha256": expanded.plan_sha256,
                "artifacts": artifacts,
                "input": plan.input_uri(),
                "expected_outputs": plan.expected_outputs_uri(),
                "results": expanded.cells.iter().map(|cell| (&cell.id, &cell.result_uri)).collect::<std::collections::BTreeMap<_, _>>(),
            }))
        }
        PlanCommand::GithubOutputs { plan, output } => {
            let plan = ExperimentPlan::from_toml_file(&plan)?;
            let expanded = plan.expand()?;
            let standalone_timing: Vec<_> = expanded
                .cells
                .iter()
                .filter(|cell| cell.kind == r8_benchctl::CellKind::StandaloneTiming)
                .cloned()
                .collect();
            let standalone_diagnostic: Vec<_> = expanded
                .cells
                .iter()
                .filter(|cell| cell.kind == r8_benchctl::CellKind::StandaloneDiagnostic)
                .cloned()
                .collect();
            let gradle: Vec<_> = expanded
                .cells
                .iter()
                .filter(|cell| cell.kind == r8_benchctl::CellKind::GradleTiming)
                .cloned()
                .collect();
            let mut file = OpenOptions::new()
                .create(true)
                .append(true)
                .open(&output)
                .with_context(|| format!("failed to open {}", output.display()))?;
            writeln!(
                file,
                "standalone_timing_matrix={}",
                serde_json::to_string(&github_strategy_cells(&standalone_timing))?
            )?;
            writeln!(
                file,
                "standalone_diagnostic_matrix={}",
                serde_json::to_string(&github_strategy_cells(&standalone_diagnostic))?
            )?;
            writeln!(
                file,
                "gradle_matrix={}",
                serde_json::to_string(&github_strategy_cells(&gradle))?
            )?;
            writeln!(file, "standalone_timing_count={}", standalone_timing.len())?;
            writeln!(
                file,
                "standalone_diagnostic_count={}",
                standalone_diagnostic.len()
            )?;
            writeln!(file, "gradle_count={}", gradle.len())?;
            writeln!(file, "max_parallel={}", expanded.max_parallel)?;
            writeln!(
                file,
                "diagnostic_max_parallel={}",
                plan.diagnostics.max_parallel
            )?;
            writeln!(file, "plan_sha256={}", expanded.plan_sha256)?;
            writeln!(file, "result_root={}", plan.result_root_uri()?)?;
            Ok(())
        }
    }
}

fn run_schema(command: SchemaCommand) -> Result<()> {
    match command {
        SchemaCommand::Plan { output } => emit_json(output.as_deref(), &experiment_plan_schema()),
        SchemaCommand::Evidence { output } => {
            emit_json(output.as_deref(), &evidence_manifest_schema())
        }
    }
}

fn run_evidence(command: EvidenceCommand) -> Result<()> {
    match command {
        EvidenceCommand::Verify { manifest } => {
            let manifest = parse_evidence_manifest(&manifest)?;
            print_json(&serde_json::json!({
                "valid": true,
                "experiment_id": manifest.experiment_id,
                "cell_id": manifest.cell_id,
                "state": manifest.state,
                "files": manifest.files.len(),
            }))
        }
        EvidenceCommand::Upload {
            plan,
            cell_id,
            result_dir,
            gcloud,
            dry_run,
        } => {
            let plan = ExperimentPlan::from_toml_file(&plan)?;
            print_json(&upload_evidence(UploadEvidenceRequest {
                plan: &plan,
                cell_id: &cell_id,
                result_dir: &result_dir,
                gcloud: &gcloud,
                dry_run,
            })?)
        }
        EvidenceCommand::VerifyBundle {
            manifest,
            bundle,
            extraction_root,
        } => {
            verify_evidence_bundle(&manifest, &bundle, &extraction_root)?;
            print_json(&serde_json::json!({"valid": true}))
        }
    }
}

fn run_hash(args: HashArgs) -> Result<()> {
    if let Some(expected) = args.expect {
        ensure_file_hash(&args.path, &expected)?;
    }
    println!("{}", r8_benchctl::sha256_file(&args.path)?);
    Ok(())
}

fn emit_json(path: Option<&Path>, value: &impl serde::Serialize) -> Result<()> {
    if let Some(path) = path {
        write_json(path, value)
    } else {
        print_json(value)
    }
}

fn print_json(value: &impl serde::Serialize) -> Result<()> {
    let stdout = io::stdout();
    let mut lock = stdout.lock();
    serde_json::to_writer_pretty(&mut lock, value).context("failed to serialize JSON")?;
    writeln!(lock).context("failed to write stdout")
}
