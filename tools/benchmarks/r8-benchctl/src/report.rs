// Copyright (c) 2026, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

use std::collections::BTreeMap;
use std::fmt::Write as _;
use std::fs;
use std::path::Path;

use anyhow::{Context, Result, ensure};
use schemars::JsonSchema;
use serde::{Deserialize, Serialize};

use crate::cell::verify_manifest_files;
use crate::external::VERIFIED_BUNDLE_MARKER;
use crate::{
    CellKind, EvidenceManifest, EvidenceState, ExpandedCell, ExperimentPlan, GradleCacheMode,
    PairOrder, RunMetrics, Variant, parse_evidence_manifest,
};

#[derive(Clone, Copy, Debug, Deserialize, Serialize, JsonSchema, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum CellStatus {
    Missing,
    Running,
    Complete,
    Failed,
    Invalid,
}

#[derive(Clone, Debug, Deserialize, Serialize, JsonSchema, PartialEq)]
pub struct CellReport {
    pub id: String,
    pub kind: CellKind,
    pub comparison_id: String,
    pub runtime_id: String,
    pub status: CellStatus,
    pub order: crate::PairOrder,
    pub pair_index: u16,
    pub result_uri: String,
    pub error: Option<String>,
    pub delta: Option<PairDelta>,
}

#[derive(Clone, Debug, Deserialize, Serialize, JsonSchema, PartialEq)]
pub struct PairDelta {
    pub wall_percent: f64,
    pub user_percent: f64,
    pub system_percent: Option<f64>,
    pub max_rss_percent: f64,
    pub peak_footprint_percent: f64,
    pub instructions_percent: Option<f64>,
    pub cycles_percent: Option<f64>,
}

#[derive(Clone, Debug, Deserialize, Serialize, JsonSchema, PartialEq)]
pub struct GroupSummary {
    pub comparison_id: String,
    pub runtime_id: String,
    pub kind: CellKind,
    pub gradle_cache_mode: Option<GradleCacheMode>,
    pub completed_pairs: usize,
    pub wall_percent: Distribution,
    pub user_percent: Distribution,
    pub system_percent: Option<Distribution>,
    pub max_rss_percent: Distribution,
    pub peak_footprint_percent: Distribution,
    pub instructions_percent: Option<Distribution>,
    pub cycles_percent: Option<Distribution>,
    /// Existing distribution fields remain pooled for JSON compatibility.
    #[serde(default)]
    pub order_summary: OrderSummary,
}

#[derive(Clone, Debug, Default, Deserialize, Serialize, JsonSchema, PartialEq)]
pub struct OrderSummary {
    /// False when reading a legacy report that did not retain order strata.
    pub available: bool,
    pub ab_pairs: usize,
    pub ba_pairs: usize,
    pub abba_cells: usize,
    pub ab_mean: Option<PairDelta>,
    pub ba_mean: Option<PairDelta>,
    /// Equal weight for AB and BA stratum means, not for individual pairs.
    /// None for one-sided orders or mixed ABBA/paired observations.
    pub balanced_mean: Option<PairDelta>,
    pub wall_order_gap_percentage_points: Option<f64>,
}

#[derive(Clone, Debug, Deserialize, Serialize, JsonSchema, PartialEq)]
pub struct Distribution {
    pub mean: f64,
    pub median: f64,
    pub minimum: f64,
    pub maximum: f64,
    pub improved: usize,
    pub regressed: usize,
}

#[derive(Clone, Debug, Deserialize, Serialize, JsonSchema, PartialEq)]
pub struct MatrixReport {
    pub experiment_id: String,
    pub plan_sha256: String,
    pub expected_cells: usize,
    pub complete_cells: usize,
    pub running_cells: usize,
    pub failed_cells: usize,
    pub invalid_cells: usize,
    pub missing_cells: usize,
    pub cells: Vec<CellReport>,
    pub groups: Vec<GroupSummary>,
}

#[derive(Clone, Debug, PartialEq, Eq, PartialOrd, Ord)]
struct GroupKey {
    comparison_id: String,
    runtime_id: String,
    kind: String,
    cache_mode: String,
}

pub fn aggregate_local(
    plan: &ExperimentPlan,
    result_root: &Path,
    verify_files: bool,
) -> Result<MatrixReport> {
    let expanded = plan.expand()?;
    let mut reports = Vec::with_capacity(expanded.cells.len());
    let mut grouped: BTreeMap<GroupKey, (ExpandedCell, Vec<(PairOrder, PairDelta)>)> =
        BTreeMap::new();
    for cell in &expanded.cells {
        let local_root = result_root.join(&cell.id);
        let manifest_path = local_root.join("result.json");
        let (status, error, delta) = if !manifest_path.is_file() {
            (CellStatus::Missing, None, None)
        } else {
            match parse_evidence_manifest(&manifest_path).and_then(|manifest| {
                verify_identity(&manifest, &expanded.plan_sha256, cell)?;
                if verify_files && manifest.state == EvidenceState::Complete {
                    let marker = local_root.join(VERIFIED_BUNDLE_MARKER);
                    if marker.is_file() {
                        let bundle = manifest
                            .bundle
                            .as_ref()
                            .context("complete manifest has no evidence bundle")?;
                        ensure!(
                            fs::read_to_string(marker)?.trim() == bundle.sha256,
                            "verified bundle marker differs"
                        );
                    } else {
                        verify_manifest_files(&local_root, &manifest)?;
                    }
                }
                Ok(manifest)
            }) {
                Ok(manifest) => {
                    let status = match manifest.state {
                        EvidenceState::Running => CellStatus::Running,
                        EvidenceState::Complete => CellStatus::Complete,
                        EvidenceState::Failed => CellStatus::Failed,
                        EvidenceState::Invalid => CellStatus::Invalid,
                    };
                    let delta = if status == CellStatus::Complete {
                        Some(pair_delta(&manifest)?)
                    } else {
                        None
                    };
                    (status, None, delta)
                }
                Err(error) => (CellStatus::Invalid, Some(format!("{error:#}")), None),
            }
        };
        if let Some(delta) = &delta {
            let key = GroupKey {
                comparison_id: cell.comparison_id.clone(),
                runtime_id: cell.runtime_id.clone(),
                kind: format!("{:?}", cell.kind),
                cache_mode: format!("{:?}", cell.gradle_cache_mode),
            };
            grouped
                .entry(key)
                .or_insert_with(|| (cell.clone(), Vec::new()))
                .1
                .push((cell.order, delta.clone()));
        }
        reports.push(CellReport {
            id: cell.id.clone(),
            kind: cell.kind,
            comparison_id: cell.comparison_id.clone(),
            runtime_id: cell.runtime_id.clone(),
            status,
            order: cell.order,
            pair_index: cell.pair_index,
            result_uri: cell.result_uri.clone(),
            error,
            delta,
        });
    }

    let groups = grouped
        .into_values()
        .map(|(cell, values)| summarize_group(&cell, &values))
        .collect();
    Ok(MatrixReport {
        experiment_id: expanded.experiment_id,
        plan_sha256: expanded.plan_sha256,
        expected_cells: reports.len(),
        complete_cells: count_status(&reports, CellStatus::Complete),
        running_cells: count_status(&reports, CellStatus::Running),
        failed_cells: count_status(&reports, CellStatus::Failed),
        invalid_cells: count_status(&reports, CellStatus::Invalid),
        missing_cells: count_status(&reports, CellStatus::Missing),
        cells: reports,
        groups,
    })
}

fn count_status(reports: &[CellReport], status: CellStatus) -> usize {
    reports
        .iter()
        .filter(|report| report.status == status)
        .count()
}

fn verify_identity(
    manifest: &EvidenceManifest,
    plan_sha256: &str,
    cell: &ExpandedCell,
) -> Result<()> {
    ensure!(
        manifest.plan_sha256 == plan_sha256,
        "manifest plan fingerprint differs"
    );
    ensure!(manifest.cell == *cell, "manifest expanded cell differs");
    Ok(())
}

fn pair_delta(manifest: &EvidenceManifest) -> Result<PairDelta> {
    let control = variant_mean(manifest, Variant::Control)?;
    let candidate = variant_mean(manifest, Variant::Candidate)?;
    Ok(PairDelta {
        wall_percent: percent(control.wall_seconds, candidate.wall_seconds)?,
        user_percent: percent(control.user_seconds, candidate.user_seconds)?,
        system_percent: if control.system_seconds > 0.0 {
            Some(percent(control.system_seconds, candidate.system_seconds)?)
        } else {
            None
        },
        max_rss_percent: percent(control.max_rss_bytes as f64, candidate.max_rss_bytes as f64)?,
        peak_footprint_percent: percent(
            control.peak_footprint_bytes as f64,
            candidate.peak_footprint_bytes as f64,
        )?,
        instructions_percent: optional_percent(control.instructions, candidate.instructions)?,
        cycles_percent: optional_percent(control.cycles, candidate.cycles)?,
    })
}

fn variant_mean(manifest: &EvidenceManifest, variant: Variant) -> Result<RunMetrics> {
    let matches: Vec<_> = manifest
        .runs
        .iter()
        .filter(|run| run.variant == variant)
        .collect();
    ensure!(!matches.is_empty(), "cell has no {:?} run", variant);
    let count = matches.len() as f64;
    Ok(RunMetrics {
        wall_seconds: matches
            .iter()
            .map(|run| run.metrics.wall_seconds)
            .sum::<f64>()
            / count,
        user_seconds: matches
            .iter()
            .map(|run| run.metrics.user_seconds)
            .sum::<f64>()
            / count,
        system_seconds: matches
            .iter()
            .map(|run| run.metrics.system_seconds)
            .sum::<f64>()
            / count,
        max_rss_bytes: mean_u64(matches.iter().map(|run| Some(run.metrics.max_rss_bytes)))
            .expect("RSS is always present"),
        peak_footprint_bytes: mean_u64(
            matches
                .iter()
                .map(|run| Some(run.metrics.peak_footprint_bytes)),
        )
        .expect("peak footprint is always present"),
        instructions: mean_u64(matches.iter().map(|run| run.metrics.instructions)),
        cycles: mean_u64(matches.iter().map(|run| run.metrics.cycles)),
    })
}

fn mean_u64(values: impl Iterator<Item = Option<u64>>) -> Option<u64> {
    let values: Option<Vec<_>> = values.collect();
    let values = values?;
    (!values.is_empty()).then(|| {
        let sum: u128 = values.iter().map(|value| u128::from(*value)).sum();
        u64::try_from(sum / values.len() as u128).unwrap_or(u64::MAX)
    })
}

fn percent(control: f64, candidate: f64) -> Result<f64> {
    ensure!(
        control.is_finite() && control > 0.0,
        "control metric must be positive"
    );
    ensure!(
        candidate.is_finite() && candidate >= 0.0,
        "candidate metric is invalid"
    );
    let delta = (candidate / control - 1.0) * 100.0;
    ensure!(delta.is_finite(), "metric percentage overflowed");
    Ok(delta)
}

fn optional_percent(control: Option<u64>, candidate: Option<u64>) -> Result<Option<f64>> {
    match (control, candidate) {
        (Some(control), Some(candidate)) => Ok(Some(percent(control as f64, candidate as f64)?)),
        (None, None) => Ok(None),
        _ => Ok(None),
    }
}

fn summarize_group(cell: &ExpandedCell, ordered: &[(PairOrder, PairDelta)]) -> GroupSummary {
    let values: Vec<_> = ordered.iter().map(|(_, value)| value).collect();
    GroupSummary {
        comparison_id: cell.comparison_id.clone(),
        runtime_id: cell.runtime_id.clone(),
        kind: cell.kind,
        gradle_cache_mode: cell.gradle_cache_mode,
        completed_pairs: values.len(),
        wall_percent: distribution(values.iter().map(|value| value.wall_percent)),
        user_percent: distribution(values.iter().map(|value| value.user_percent)),
        system_percent: optional_distribution(values.iter().map(|value| value.system_percent)),
        max_rss_percent: distribution(values.iter().map(|value| value.max_rss_percent)),
        peak_footprint_percent: distribution(
            values.iter().map(|value| value.peak_footprint_percent),
        ),
        instructions_percent: optional_distribution(
            values.iter().map(|value| value.instructions_percent),
        ),
        cycles_percent: optional_distribution(values.iter().map(|value| value.cycles_percent)),
        order_summary: summarize_orders(ordered),
    }
}

fn summarize_orders(ordered: &[(PairOrder, PairDelta)]) -> OrderSummary {
    let ab: Vec<_> = ordered
        .iter()
        .filter_map(|(order, value)| (*order == PairOrder::AB).then_some(value))
        .collect();
    let ba: Vec<_> = ordered
        .iter()
        .filter_map(|(order, value)| (*order == PairOrder::BA).then_some(value))
        .collect();
    let abba_cells = ordered
        .iter()
        .filter(|(order, _)| *order == PairOrder::ABBA)
        .count();
    let ab_mean = mean_delta(&ab);
    let ba_mean = mean_delta(&ba);
    let (balanced_mean, wall_order_gap_percentage_points) = match (&ab_mean, &ba_mean) {
        (Some(ab), Some(ba)) if abba_cells == 0 => (
            mean_delta(&[ab, ba]),
            Some(ab.wall_percent - ba.wall_percent),
        ),
        _ => (None, None),
    };
    OrderSummary {
        available: true,
        ab_pairs: ab.len(),
        ba_pairs: ba.len(),
        abba_cells,
        ab_mean,
        ba_mean,
        balanced_mean,
        wall_order_gap_percentage_points,
    }
}

fn mean_delta(values: &[&PairDelta]) -> Option<PairDelta> {
    if values.is_empty() {
        return None;
    }
    Some(PairDelta {
        wall_percent: distribution(values.iter().map(|value| value.wall_percent)).mean,
        user_percent: distribution(values.iter().map(|value| value.user_percent)).mean,
        system_percent: optional_distribution(values.iter().map(|value| value.system_percent))
            .map(|value| value.mean),
        max_rss_percent: distribution(values.iter().map(|value| value.max_rss_percent)).mean,
        peak_footprint_percent: distribution(
            values.iter().map(|value| value.peak_footprint_percent),
        )
        .mean,
        instructions_percent: optional_distribution(
            values.iter().map(|value| value.instructions_percent),
        )
        .map(|value| value.mean),
        cycles_percent: optional_distribution(values.iter().map(|value| value.cycles_percent))
            .map(|value| value.mean),
    })
}

fn optional_distribution(values: impl Iterator<Item = Option<f64>>) -> Option<Distribution> {
    let values: Option<Vec<_>> = values.collect();
    let values = values?;
    (!values.is_empty() && values.iter().all(|value| value.is_finite()))
        .then(|| distribution(values.into_iter()))
}

fn distribution(values: impl Iterator<Item = f64>) -> Distribution {
    let mut values: Vec<_> = values.filter(|value| value.is_finite()).collect();
    values.sort_by(f64::total_cmp);
    let mean = values.iter().sum::<f64>() / values.len() as f64;
    let median = if values.len() % 2 == 1 {
        values[values.len() / 2]
    } else {
        (values[values.len() / 2 - 1] + values[values.len() / 2]) / 2.0
    };
    Distribution {
        mean,
        median,
        minimum: values[0],
        maximum: values[values.len() - 1],
        improved: values.iter().filter(|value| **value < 0.0).count(),
        regressed: values.iter().filter(|value| **value > 0.0).count(),
    }
}

pub fn markdown(report: &MatrixReport) -> Result<String> {
    let mut output = String::new();
    writeln!(&mut output, "# R8 benchmark {}", report.experiment_id)?;
    writeln!(&mut output)?;
    writeln!(&mut output, "Plan: `{}`", report.plan_sha256)?;
    writeln!(&mut output)?;
    writeln!(
        &mut output,
        "Cells: {} complete, {} running, {} failed, {} invalid, {} missing of {}.",
        report.complete_cells,
        report.running_cells,
        report.failed_cells,
        report.invalid_cells,
        report.missing_cells,
        report.expected_cells
    )?;
    if !report.groups.is_empty() {
        writeln!(&mut output)?;
        writeln!(
            &mut output,
            "| Comparison | Runtime | Kind | Pairs | Pooled wall mean | Pooled wall median | Wall range | Pooled user mean | Pooled RSS mean | Pooled footprint mean | Direction |"
        )?;
        writeln!(
            &mut output,
            "|---|---|---|---:|---:|---:|---:|---:|---:|---:|---:|"
        )?;
        for group in &report.groups {
            writeln!(
                &mut output,
                "| {} | {} | {:?} | {} | {:+.3}% | {:+.3}% | {:+.3}%..{:+.3}% | {:+.3}% | {:+.3}% | {:+.3}% | {}/{} |",
                group.comparison_id,
                group.runtime_id,
                group.kind,
                group.completed_pairs,
                group.wall_percent.mean,
                group.wall_percent.median,
                group.wall_percent.minimum,
                group.wall_percent.maximum,
                group.user_percent.mean,
                group.max_rss_percent.mean,
                group.peak_footprint_percent.mean,
                group.wall_percent.improved,
                group.wall_percent.regressed,
            )?;
        }
        writeln!(&mut output)?;
        writeln!(&mut output, "## AB/BA order-balanced means")?;
        writeln!(&mut output)?;
        writeln!(
            &mut output,
            "Each order stratum receives equal weight. NA means missing AB/BA support, mixed ABBA cells, or incomplete metric support. ABBA diagnostics remain in their separate pooled group."
        )?;
        writeln!(&mut output)?;
        writeln!(
            &mut output,
            "| Comparison | Runtime | Kind | AB | BA | ABBA | Wall | User | System | RSS | Footprint | Instructions | Cycles | AB-BA wall (pp) |"
        )?;
        writeln!(
            &mut output,
            "|---|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|"
        )?;
        for group in &report.groups {
            let order = &group.order_summary;
            let balanced = order.balanced_mean.as_ref();
            writeln!(
                &mut output,
                "| {} | {} | {:?} | {} | {} | {} | {} | {} | {} | {} | {} | {} | {} | {} |",
                group.comparison_id,
                group.runtime_id,
                group.kind,
                order.ab_pairs,
                order.ba_pairs,
                order.abba_cells,
                percent_text(balanced.map(|value| value.wall_percent)),
                percent_text(balanced.map(|value| value.user_percent)),
                percent_text(balanced.and_then(|value| value.system_percent)),
                percent_text(balanced.map(|value| value.max_rss_percent)),
                percent_text(balanced.map(|value| value.peak_footprint_percent)),
                percent_text(balanced.and_then(|value| value.instructions_percent)),
                percent_text(balanced.and_then(|value| value.cycles_percent)),
                order
                    .wall_order_gap_percentage_points
                    .map_or_else(|| "NA".to_string(), |value| format!("{value:+.3}"),),
            )?;
        }
    }
    let completed: Vec<_> = report
        .cells
        .iter()
        .filter_map(|cell| cell.delta.as_ref().map(|delta| (cell, delta)))
        .collect();
    if !completed.is_empty() {
        writeln!(&mut output)?;
        writeln!(&mut output, "## Paired cells")?;
        writeln!(&mut output)?;
        writeln!(
            &mut output,
            "| Cell | Order | Wall | User | RSS | Footprint |"
        )?;
        writeln!(&mut output, "|---|---|---:|---:|---:|---:|")?;
        for (cell, delta) in completed {
            writeln!(
                &mut output,
                "| {} | {:?} | {:+.3}% | {:+.3}% | {:+.3}% | {:+.3}% |",
                cell.id,
                cell.order,
                delta.wall_percent,
                delta.user_percent,
                delta.max_rss_percent,
                delta.peak_footprint_percent,
            )?;
        }
    }
    Ok(output)
}

fn percent_text(value: Option<f64>) -> String {
    value.map_or_else(|| "NA".to_string(), |value| format!("{value:+.3}%"))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn delta(wall: f64) -> PairDelta {
        PairDelta {
            wall_percent: wall,
            user_percent: wall,
            system_percent: Some(wall),
            max_rss_percent: wall,
            peak_footprint_percent: wall,
            instructions_percent: Some(wall),
            cycles_percent: Some(wall),
        }
    }

    #[test]
    fn weights_order_strata_equally_with_unequal_counts() {
        let mut values = vec![(PairOrder::AB, delta(10.0)); 3];
        values.extend(vec![(PairOrder::BA, delta(-10.0)); 2]);
        let summary = summarize_orders(&values);
        assert_eq!(summary.ab_pairs, 3);
        assert_eq!(summary.ba_pairs, 2);
        assert_eq!(summary.abba_cells, 0);
        assert_eq!(summary.ab_mean.as_ref().unwrap().wall_percent, 10.0);
        assert_eq!(summary.ba_mean.as_ref().unwrap().wall_percent, -10.0);
        assert_eq!(summary.balanced_mean.unwrap().wall_percent, 0.0);
        assert_eq!(summary.wall_order_gap_percentage_points, Some(20.0));
        assert_eq!(
            distribution(values.iter().map(|(_, value)| value.wall_percent)).mean,
            2.0
        );
    }

    #[test]
    fn does_not_publish_a_balanced_estimate_for_one_order() {
        let summary = summarize_orders(&[(PairOrder::AB, delta(-5.0))]);
        assert!(summary.available);
        assert_eq!(summary.ab_pairs, 1);
        assert_eq!(summary.ba_pairs, 0);
        assert!(summary.balanced_mean.is_none());
        assert!(summary.wall_order_gap_percentage_points.is_none());
    }

    #[test]
    fn optional_metrics_require_complete_support_in_both_orders() {
        let mut missing = delta(2.0);
        missing.instructions_percent = None;
        missing.cycles_percent = None;
        let summary = summarize_orders(&[(PairOrder::AB, delta(1.0)), (PairOrder::BA, missing)]);
        let balanced = summary.balanced_mean.unwrap();
        assert_eq!(balanced.wall_percent, 1.5);
        assert!(balanced.instructions_percent.is_none());
        assert!(balanced.cycles_percent.is_none());
        assert!(optional_distribution([Some(1.0), None].into_iter()).is_none());
        assert!(optional_distribution([Some(1.0), Some(f64::NAN)].into_iter()).is_none());
    }

    #[test]
    fn does_not_mix_abba_with_paired_order_estimates() {
        let summary = summarize_orders(&[
            (PairOrder::AB, delta(1.0)),
            (PairOrder::BA, delta(2.0)),
            (PairOrder::ABBA, delta(3.0)),
        ]);
        assert_eq!(summary.abba_cells, 1);
        assert!(summary.balanced_mean.is_none());
        assert!(
            summarize_orders(&[(PairOrder::ABBA, delta(3.0))])
                .balanced_mean
                .is_none()
        );
    }

    #[test]
    fn order_summary_does_not_depend_on_cell_iteration_order() {
        let mut values = vec![
            (PairOrder::AB, delta(3.0)),
            (PairOrder::BA, delta(-8.0)),
            (PairOrder::AB, delta(-1.0)),
        ];
        let summary = summarize_orders(&values);
        values.reverse();
        assert_eq!(summarize_orders(&values), summary);
    }

    #[test]
    fn rejects_percentage_overflow() {
        assert!(percent(1e-300, 1e300).is_err());
    }

    #[test]
    fn distribution_reports_mean_median_and_signs() {
        let value = distribution([-2.0, -1.0, 3.0, 9.0].into_iter());
        assert_eq!(value.mean, 2.25);
        assert_eq!(value.median, 1.0);
        assert_eq!(value.minimum, -2.0);
        assert_eq!(value.maximum, 9.0);
        assert_eq!(value.improved, 2);
        assert_eq!(value.regressed, 2);
    }
}
