// Copyright (c) 2026, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

use std::collections::{BTreeMap, BTreeSet};
use std::fs::{self, File};
use std::io::{BufReader, BufWriter, Read, Seek, SeekFrom, Write};
use std::path::Path;
use std::process::Command;

use anyhow::{Context, Result, ensure};
use serde::{Deserialize, Serialize};

use crate::{sha256_file, write_json};

pub const LAYOUT_REPLAY_REPORT_SCHEMA_VERSION: u32 = 1;
const LAYOUT_TRACE_MAGIC: u32 = 0x52384c54;
const LAYOUT_TRACE_SCHEMA_VERSION: u32 = 1;
const MAX_LAYOUT_TRACE_EVENT_INTS: u32 = 256 * 1024 * 1024;

#[derive(Clone, Debug, Deserialize, Serialize, PartialEq, Eq)]
pub struct LayoutTraceSliceSummary {
    pub trace_schema_version: u32,
    pub fingerprint: String,
    pub source_methods: u32,
    pub selected_methods: u32,
    pub missing_filter_methods: usize,
    pub event_ints: u64,
    pub output_sha256: String,
}

pub fn slice_layout_trace(
    trace_path: &Path,
    method_filter_path: &Path,
    output_path: &Path,
) -> Result<LayoutTraceSliceSummary> {
    ensure!(
        trace_path.is_file(),
        "missing trace {}",
        trace_path.display()
    );
    ensure!(
        method_filter_path.is_file(),
        "missing method filter {}",
        method_filter_path.display()
    );
    let filter_text = fs::read_to_string(method_filter_path)
        .with_context(|| format!("failed to read {}", method_filter_path.display()))?;
    let mut remaining = BTreeSet::new();
    for line in filter_text.lines() {
        let method_id = line.trim();
        if method_id.is_empty() || method_id.starts_with('#') {
            continue;
        }
        ensure!(
            method_id.len() == 64 && method_id.bytes().all(|byte| byte.is_ascii_hexdigit()),
            "invalid method id in {}: {method_id}",
            method_filter_path.display()
        );
        remaining.insert(method_id.to_owned());
    }
    ensure!(!remaining.is_empty(), "layout trace method filter is empty");

    let parent = output_path.parent().unwrap_or_else(|| Path::new("."));
    fs::create_dir_all(parent).with_context(|| format!("failed to create {}", parent.display()))?;
    let mut body = tempfile::NamedTempFile::new_in(parent)
        .with_context(|| format!("failed to create temporary file in {}", parent.display()))?;
    let mut input = BufReader::new(
        File::open(trace_path)
            .with_context(|| format!("failed to open {}", trace_path.display()))?,
    );
    ensure!(
        read_u32(&mut input)? == LAYOUT_TRACE_MAGIC,
        "invalid layout trace magic"
    );
    let trace_schema_version = read_u32(&mut input)?;
    ensure!(
        trace_schema_version == LAYOUT_TRACE_SCHEMA_VERSION,
        "unsupported layout trace schema {trace_schema_version}"
    );
    let fingerprint_bytes = read_java_utf_bytes(&mut input)?;
    let fingerprint = String::from_utf8(fingerprint_bytes.clone())
        .context("layout trace fingerprint is not UTF-8")?;
    ensure!(!fingerprint.is_empty(), "layout trace fingerprint is empty");
    let source_methods = read_u32(&mut input)?;
    let mut selected_methods = 0_u32;
    let mut event_ints = 0_u64;
    for _ in 0..source_methods {
        let method_id_bytes = read_java_utf_bytes(&mut input)?;
        let method_id = String::from_utf8(method_id_bytes.clone())
            .context("layout trace method id is not UTF-8")?;
        ensure!(
            method_id.len() == 64 && method_id.bytes().all(|byte| byte.is_ascii_hexdigit()),
            "invalid method id in layout trace: {method_id}"
        );
        let mut fixed = [0_u8; 20];
        input
            .read_exact(&mut fixed)
            .context("truncated layout trace method header")?;
        let event_size = u32::from_be_bytes(fixed[16..20].try_into().unwrap());
        ensure!(
            event_size <= MAX_LAYOUT_TRACE_EVENT_INTS,
            "layout trace method has too many event ints: {event_size}"
        );
        let event_bytes = u64::from(event_size) * 4;
        if remaining.remove(&method_id) {
            write_java_utf_bytes(&mut body, &method_id_bytes)?;
            body.write_all(&fixed)?;
            let copied = std::io::copy(&mut input.by_ref().take(event_bytes), &mut body)?;
            ensure!(copied == event_bytes, "truncated layout trace method body");
            selected_methods += 1;
            event_ints += u64::from(event_size);
        } else {
            input
                .seek(SeekFrom::Current(event_bytes.try_into().unwrap()))
                .context("failed to skip layout trace method body")?;
        }
    }
    let mut trailing = [0_u8; 1];
    ensure!(
        input.read(&mut trailing)? == 0,
        "trailing layout trace data"
    );
    ensure!(
        selected_methods > 0,
        "method filter did not match the layout trace"
    );

    body.flush()?;
    let mut body_input = BufReader::new(body.reopen()?);
    let mut output = tempfile::NamedTempFile::new_in(parent)
        .with_context(|| format!("failed to create temporary file in {}", parent.display()))?;
    {
        let mut writer = BufWriter::new(output.as_file_mut());
        writer.write_all(&LAYOUT_TRACE_MAGIC.to_be_bytes())?;
        writer.write_all(&trace_schema_version.to_be_bytes())?;
        write_java_utf_bytes(&mut writer, &fingerprint_bytes)?;
        writer.write_all(&selected_methods.to_be_bytes())?;
        std::io::copy(&mut body_input, &mut writer)?;
        writer.flush()?;
    }
    output
        .persist(output_path)
        .map_err(|error| error.error)
        .with_context(|| format!("failed to persist {}", output_path.display()))?;
    Ok(LayoutTraceSliceSummary {
        trace_schema_version,
        fingerprint,
        source_methods,
        selected_methods,
        missing_filter_methods: remaining.len(),
        event_ints,
        output_sha256: sha256_file(output_path)?,
    })
}

fn read_u32(input: &mut impl Read) -> Result<u32> {
    let mut bytes = [0_u8; 4];
    input.read_exact(&mut bytes)?;
    Ok(u32::from_be_bytes(bytes))
}

fn read_java_utf_bytes(input: &mut impl Read) -> Result<Vec<u8>> {
    let mut size = [0_u8; 2];
    input.read_exact(&mut size)?;
    let mut bytes = vec![0_u8; usize::from(u16::from_be_bytes(size))];
    input.read_exact(&mut bytes)?;
    Ok(bytes)
}

fn write_java_utf_bytes(output: &mut impl Write, bytes: &[u8]) -> Result<()> {
    let size: u16 = bytes
        .len()
        .try_into()
        .context("Java UTF value is too long")?;
    output.write_all(&size.to_be_bytes())?;
    output.write_all(bytes)?;
    Ok(())
}

pub struct LayoutPairRequest<'a> {
    pub runner: &'a Path,
    pub java_bin: &'a Path,
    pub java_args: &'a [String],
    pub trace: &'a Path,
    pub expected_fingerprint: Option<&'a str>,
    pub control_list: &'a str,
    pub control_positions: &'a str,
    pub candidate_list: &'a str,
    pub candidate_positions: &'a str,
    pub pairs: u16,
    pub warmup_iterations: u16,
    pub measured_iterations: u16,
    pub result_dir: &'a Path,
}

#[derive(Clone, Debug, Deserialize, Serialize, PartialEq, Eq)]
pub struct LayoutRunSummary {
    pub wall_median_nanos: u64,
    pub cpu_median_nanos: i64,
    pub allocated_median_bytes: i64,
}

#[derive(Clone, Debug, Deserialize, Serialize, PartialEq, Eq)]
pub struct LayoutRunResult {
    pub schema_version: u32,
    pub trace_schema_version: u32,
    pub trace_sha256: String,
    pub fingerprint: String,
    pub list_backend: String,
    pub position_backend: String,
    pub methods: u64,
    pub event_ints: u64,
    pub warmup_iterations: u16,
    pub measured_iterations: u16,
    pub checksum: String,
    pub cpu_supported: bool,
    pub allocation_supported: bool,
    pub summary: LayoutRunSummary,
}

#[derive(Clone, Copy, Debug, Deserialize, Serialize, PartialEq, Eq)]
pub enum LayoutPairOrder {
    AB,
    BA,
}

#[derive(Clone, Debug, Deserialize, Serialize, PartialEq)]
pub struct LayoutMetricDelta {
    pub wall_percent: f64,
    pub cpu_percent: Option<f64>,
    pub allocated_percent: Option<f64>,
}

#[derive(Clone, Debug, Deserialize, Serialize, PartialEq)]
pub struct LayoutPairObservation {
    pub pair_index: u16,
    pub order: LayoutPairOrder,
    pub control: LayoutRunResult,
    pub candidate: LayoutRunResult,
    pub delta: LayoutMetricDelta,
}

#[derive(Clone, Debug, Deserialize, Serialize, PartialEq)]
pub struct ReplayDistribution {
    pub count: usize,
    pub mean: f64,
    pub median: f64,
    pub minimum: f64,
    pub maximum: f64,
    pub improved: usize,
    pub regressed: usize,
}

#[derive(Clone, Debug, Deserialize, Serialize, PartialEq)]
pub struct LayoutDeltaSummary {
    pub wall_percent: ReplayDistribution,
    pub cpu_percent: Option<ReplayDistribution>,
    pub allocated_percent: Option<ReplayDistribution>,
}

#[derive(Clone, Debug, Deserialize, Serialize, PartialEq)]
pub struct LayoutOrderBias {
    pub wall_percentage_points: Option<f64>,
    pub cpu_percentage_points: Option<f64>,
    pub allocated_percentage_points: Option<f64>,
}

#[derive(Clone, Debug, Deserialize, Serialize, PartialEq)]
pub struct LayoutPairReport {
    pub schema_version: u32,
    pub trace_sha256: String,
    pub fingerprint: String,
    pub checksum: String,
    pub control: LayoutDescription,
    pub candidate: LayoutDescription,
    pub pairs: Vec<LayoutPairObservation>,
    pub summary: LayoutDeltaSummary,
    pub by_order: BTreeMap<String, LayoutDeltaSummary>,
    pub order_bias_ab_minus_ba: LayoutOrderBias,
}

#[derive(Clone, Debug, Deserialize, Serialize, PartialEq, Eq)]
pub struct LayoutDescription {
    pub list: String,
    pub positions: String,
}

pub fn run_layout_pairs(request: LayoutPairRequest<'_>) -> Result<LayoutPairReport> {
    ensure!(
        request.runner.is_file(),
        "missing runner {}",
        request.runner.display()
    );
    ensure!(
        request.java_bin.is_file(),
        "missing Java binary {}",
        request.java_bin.display()
    );
    ensure!(
        request.trace.is_file(),
        "missing trace {}",
        request.trace.display()
    );
    ensure!(request.pairs > 0, "pairs must be positive");
    ensure!(
        request.measured_iterations > 0,
        "iterations must be positive"
    );
    fs::create_dir_all(request.result_dir)
        .with_context(|| format!("failed to create {}", request.result_dir.display()))?;

    let control = LayoutDescription {
        list: request.control_list.to_owned(),
        positions: request.control_positions.to_owned(),
    };
    let candidate = LayoutDescription {
        list: request.candidate_list.to_owned(),
        positions: request.candidate_positions.to_owned(),
    };
    let mut observations = Vec::with_capacity(usize::from(request.pairs));
    let mut identity: Option<(String, String, String)> = None;
    for pair_index in 1..=request.pairs {
        let order = if pair_index % 2 == 1 {
            LayoutPairOrder::AB
        } else {
            LayoutPairOrder::BA
        };
        let variants = match order {
            LayoutPairOrder::AB => [(&control, "control"), (&candidate, "candidate")],
            LayoutPairOrder::BA => [(&candidate, "candidate"), (&control, "control")],
        };
        let mut control_result = None;
        let mut candidate_result = None;
        for (ordinal, (layout, variant)) in variants.into_iter().enumerate() {
            let raw_path = request.result_dir.join(format!(
                "pair-{pair_index:02}-run-{:02}-{variant}.json",
                ordinal + 1
            ));
            let result = run_layout(&request, layout, &raw_path)?;
            validate_layout_result(&result, layout, &request)?;
            let current_identity = (
                result.trace_sha256.clone(),
                result.fingerprint.clone(),
                result.checksum.clone(),
            );
            if let Some(expected) = &identity {
                ensure!(
                    *expected == current_identity,
                    "replay identity differs in pair {pair_index} run {}",
                    ordinal + 1
                );
            } else {
                identity = Some(current_identity);
            }
            if variant == "control" {
                control_result = Some(result);
            } else {
                candidate_result = Some(result);
            }
        }
        let control_result = control_result.context("pair has no control result")?;
        let candidate_result = candidate_result.context("pair has no candidate result")?;
        let delta = delta(&control_result.summary, &candidate_result.summary)?;
        observations.push(LayoutPairObservation {
            pair_index,
            order,
            control: control_result,
            candidate: candidate_result,
            delta,
        });
    }

    let (trace_sha256, fingerprint, checksum) = identity.context("no replay identity")?;
    let summary = summarize(observations.iter().map(|observation| &observation.delta))?;
    let mut by_order = BTreeMap::new();
    for order in [LayoutPairOrder::AB, LayoutPairOrder::BA] {
        let values: Vec<_> = observations
            .iter()
            .filter(|observation| observation.order == order)
            .map(|observation| &observation.delta)
            .collect();
        if !values.is_empty() {
            by_order.insert(format!("{order:?}"), summarize(values.into_iter())?);
        }
    }
    let order_bias_ab_minus_ba = order_bias(&by_order);
    let report = LayoutPairReport {
        schema_version: LAYOUT_REPLAY_REPORT_SCHEMA_VERSION,
        trace_sha256,
        fingerprint,
        checksum,
        control,
        candidate,
        pairs: observations,
        summary,
        by_order,
        order_bias_ab_minus_ba,
    };
    write_json(&request.result_dir.join("report.json"), &report)?;
    Ok(report)
}

fn run_layout(
    request: &LayoutPairRequest<'_>,
    layout: &LayoutDescription,
    raw_path: &Path,
) -> Result<LayoutRunResult> {
    let mut command = Command::new(request.java_bin);
    command.args(request.java_args);
    command
        .arg("-jar")
        .arg(request.runner)
        .arg("--trace")
        .arg(request.trace)
        .arg("--list")
        .arg(&layout.list)
        .arg("--positions")
        .arg(&layout.positions)
        .arg("--warmup")
        .arg(request.warmup_iterations.to_string())
        .arg("--iterations")
        .arg(request.measured_iterations.to_string());
    if let Some(fingerprint) = request.expected_fingerprint {
        command.arg("--expect-fingerprint").arg(fingerprint);
    }
    let output = command
        .output()
        .with_context(|| format!("failed to start {}", request.java_bin.display()))?;
    ensure!(
        output.status.success(),
        "layout replay failed ({}):\n{}",
        output.status,
        String::from_utf8_lossy(&output.stderr)
    );
    fs::write(raw_path, &output.stdout)
        .with_context(|| format!("failed to write {}", raw_path.display()))?;
    serde_json::from_slice(&output.stdout)
        .with_context(|| format!("invalid replay result from {}", raw_path.display()))
}

fn validate_layout_result(
    result: &LayoutRunResult,
    layout: &LayoutDescription,
    request: &LayoutPairRequest<'_>,
) -> Result<()> {
    ensure!(
        result.schema_version == 1,
        "unsupported replay result schema"
    );
    ensure!(result.trace_schema_version == 1, "unsupported trace schema");
    ensure!(result.list_backend == layout.list, "list backend differs");
    ensure!(
        result.position_backend == layout.positions,
        "position backend differs"
    );
    ensure!(
        result.warmup_iterations == request.warmup_iterations,
        "warmup count differs"
    );
    ensure!(
        result.measured_iterations == request.measured_iterations,
        "iteration count differs"
    );
    ensure!(result.methods > 0, "trace has no methods");
    ensure!(result.event_ints > 0, "trace has no events");
    if let Some(expected) = request.expected_fingerprint {
        ensure!(result.fingerprint == expected, "trace fingerprint differs");
    }
    Ok(())
}

fn delta(control: &LayoutRunSummary, candidate: &LayoutRunSummary) -> Result<LayoutMetricDelta> {
    Ok(LayoutMetricDelta {
        wall_percent: percent(control.wall_median_nanos, candidate.wall_median_nanos)?,
        cpu_percent: optional_percent(control.cpu_median_nanos, candidate.cpu_median_nanos)?,
        allocated_percent: optional_percent(
            control.allocated_median_bytes,
            candidate.allocated_median_bytes,
        )?,
    })
}

fn percent(control: u64, candidate: u64) -> Result<f64> {
    ensure!(control > 0, "control metric must be positive");
    Ok((candidate as f64 / control as f64 - 1.0) * 100.0)
}

fn optional_percent(control: i64, candidate: i64) -> Result<Option<f64>> {
    if control < 0 && candidate < 0 {
        return Ok(None);
    }
    ensure!(control > 0, "control optional metric must be positive");
    ensure!(
        candidate >= 0,
        "candidate optional metric must not be negative"
    );
    Ok(Some((candidate as f64 / control as f64 - 1.0) * 100.0))
}

fn summarize<'a>(
    values: impl Iterator<Item = &'a LayoutMetricDelta>,
) -> Result<LayoutDeltaSummary> {
    let values: Vec<_> = values.collect();
    ensure!(!values.is_empty(), "cannot summarize zero replay pairs");
    Ok(LayoutDeltaSummary {
        wall_percent: distribution(values.iter().map(|value| value.wall_percent)),
        cpu_percent: optional_distribution(values.iter().map(|value| value.cpu_percent)),
        allocated_percent: optional_distribution(
            values.iter().map(|value| value.allocated_percent),
        ),
    })
}

fn distribution(values: impl Iterator<Item = f64>) -> ReplayDistribution {
    let mut values: Vec<_> = values.collect();
    values.sort_by(f64::total_cmp);
    let count = values.len();
    let mean = values.iter().sum::<f64>() / count as f64;
    let median = if count % 2 == 1 {
        values[count / 2]
    } else {
        (values[count / 2 - 1] + values[count / 2]) / 2.0
    };
    ReplayDistribution {
        count,
        mean,
        median,
        minimum: values[0],
        maximum: values[count - 1],
        improved: values.iter().filter(|value| **value < 0.0).count(),
        regressed: values.iter().filter(|value| **value > 0.0).count(),
    }
}

fn optional_distribution(values: impl Iterator<Item = Option<f64>>) -> Option<ReplayDistribution> {
    let values: Option<Vec<_>> = values.collect();
    values.map(|values| distribution(values.into_iter()))
}

fn order_bias(by_order: &BTreeMap<String, LayoutDeltaSummary>) -> LayoutOrderBias {
    let ab = by_order.get("AB");
    let ba = by_order.get("BA");
    LayoutOrderBias {
        wall_percentage_points: ab
            .zip(ba)
            .map(|(ab, ba)| ab.wall_percent.mean - ba.wall_percent.mean),
        cpu_percentage_points: ab
            .zip(ba)
            .and_then(|(ab, ba)| ab.cpu_percent.as_ref().zip(ba.cpu_percent.as_ref()))
            .map(|(ab, ba)| ab.mean - ba.mean),
        allocated_percentage_points: ab
            .zip(ba)
            .and_then(|(ab, ba)| {
                ab.allocated_percent
                    .as_ref()
                    .zip(ba.allocated_percent.as_ref())
            })
            .map(|(ab, ba)| ab.mean - ba.mean),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn distribution_is_order_independent() {
        assert_eq!(
            distribution([3.0, -1.0, 2.0, 0.0].into_iter()),
            ReplayDistribution {
                count: 4,
                mean: 1.0,
                median: 1.0,
                minimum: -1.0,
                maximum: 3.0,
                improved: 1,
                regressed: 2,
            }
        );
    }

    #[test]
    fn optional_metrics_require_consistent_support() {
        assert_eq!(optional_percent(-1, -1).unwrap(), None);
        assert!(optional_percent(-1, 10).is_err());
        let value = optional_percent(100, 90).unwrap().unwrap();
        assert!((value + 10.0).abs() < 1e-12);
    }

    #[test]
    fn slices_trace_without_loading_event_bodies() {
        let directory = tempfile::tempdir().unwrap();
        let source = directory.path().join("source.bin");
        let filter = directory.path().join("methods.txt");
        let output = directory.path().join("slice.bin");
        let first = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        let second = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
        let missing = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc";
        {
            let mut writer = BufWriter::new(File::create(&source).unwrap());
            writer.write_all(&LAYOUT_TRACE_MAGIC.to_be_bytes()).unwrap();
            writer
                .write_all(&LAYOUT_TRACE_SCHEMA_VERSION.to_be_bytes())
                .unwrap();
            write_java_utf_bytes(&mut writer, b"fingerprint").unwrap();
            writer.write_all(&2_u32.to_be_bytes()).unwrap();
            for (index, method_id) in [first, second].into_iter().enumerate() {
                write_java_utf_bytes(&mut writer, method_id.as_bytes()).unwrap();
                writer.write_all(&(index as u64 + 1).to_be_bytes()).unwrap();
                writer.write_all(&2_u32.to_be_bytes()).unwrap();
                writer.write_all(&0_u32.to_be_bytes()).unwrap();
                writer.write_all(&3_u32.to_be_bytes()).unwrap();
                for event in [1_u32, 0, index as u32] {
                    writer.write_all(&event.to_be_bytes()).unwrap();
                }
            }
            writer.flush().unwrap();
        }
        fs::write(&filter, format!("{second}\n{missing}\n")).unwrap();

        let summary = slice_layout_trace(&source, &filter, &output).unwrap();
        assert_eq!(summary.source_methods, 2);
        assert_eq!(summary.selected_methods, 1);
        assert_eq!(summary.missing_filter_methods, 1);
        assert_eq!(summary.event_ints, 3);

        let mut reader = BufReader::new(File::open(output).unwrap());
        assert_eq!(read_u32(&mut reader).unwrap(), LAYOUT_TRACE_MAGIC);
        assert_eq!(read_u32(&mut reader).unwrap(), LAYOUT_TRACE_SCHEMA_VERSION);
        assert_eq!(read_java_utf_bytes(&mut reader).unwrap(), b"fingerprint");
        assert_eq!(read_u32(&mut reader).unwrap(), 1);
        assert_eq!(read_java_utf_bytes(&mut reader).unwrap(), second.as_bytes());
    }
}
