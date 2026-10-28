// Copyright (c) 2026, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

use std::cmp::Reverse;
use std::collections::{BTreeMap, BTreeSet};
use std::fs;
use std::io::{BufRead, BufReader};
use std::path::Path;

use anyhow::{Context, Result, bail, ensure};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};

use crate::{sha256_file, write_json};

pub const CORPUS_SCHEMA_VERSION: u32 = 1;

#[derive(Clone, Debug, Deserialize, Serialize, PartialEq, Eq)]
#[serde(deny_unknown_fields)]
pub struct AllocatorMethodProfile {
    pub schema_version: u32,
    pub method_id: String,
    pub holder: String,
    pub method: String,
    pub instruction_count: u32,
    pub block_count: u32,
    pub phi_count: u32,
    pub live_interval_count: u32,
    pub wide_interval_count: u32,
    pub constrained_interval_count: u32,
    pub peak_active: u32,
    pub peak_inactive: u32,
    pub register_count: u32,
    pub spill_count: u32,
    pub split_count: u32,
    pub invoke_range_count: u32,
    pub exception_block_count: u32,
    #[serde(default)]
    pub allocator_wall_nanos: u64,
    #[serde(default)]
    pub allocator_cpu_nanos: u64,
}

impl AllocatorMethodProfile {
    fn validate(&self) -> Result<()> {
        ensure!(
            self.schema_version == CORPUS_SCHEMA_VERSION,
            "unsupported allocator profile schema {} for {}",
            self.schema_version,
            self.method_id
        );
        ensure!(self.method_id.len() == 64, "method_id is not SHA-256");
        ensure!(
            self.method_id.bytes().all(|byte| byte.is_ascii_hexdigit()),
            "method_id is not hexadecimal"
        );
        ensure!(
            self.holder.starts_with('L') && self.holder.ends_with(';'),
            "holder is not a class descriptor: {}",
            self.holder
        );
        ensure!(!self.method.is_empty(), "method descriptor is empty");
        Ok(())
    }

    fn has_feature(&self, feature: RareFeature) -> bool {
        match feature {
            RareFeature::InvokeRange => self.invoke_range_count > 0,
            RareFeature::ConstrainedRegisters => self.constrained_interval_count > 0,
            RareFeature::Spills => self.spill_count > 0,
            RareFeature::SplitIntervals => self.split_count > 0,
            RareFeature::ExceptionCfg => self.exception_block_count > 0,
            RareFeature::WideValues => self.wide_interval_count > 0,
        }
    }
}

#[derive(Clone, Copy, Debug, Deserialize, Serialize, PartialEq, Eq, PartialOrd, Ord)]
#[serde(rename_all = "snake_case")]
pub enum RareFeature {
    InvokeRange,
    ConstrainedRegisters,
    Spills,
    SplitIntervals,
    ExceptionCfg,
    WideValues,
}

const ALL_FEATURES: [RareFeature; 6] = [
    RareFeature::InvokeRange,
    RareFeature::ConstrainedRegisters,
    RareFeature::Spills,
    RareFeature::SplitIntervals,
    RareFeature::ExceptionCfg,
    RareFeature::WideValues,
];

#[derive(Clone, Debug, Serialize, PartialEq)]
pub struct FeatureSummary {
    pub feature: RareFeature,
    pub methods: usize,
    pub percent: f64,
}

#[derive(Clone, Debug, Serialize, PartialEq)]
pub struct ProfileSummary {
    pub schema_version: u32,
    pub methods: usize,
    pub holders: usize,
    pub features: Vec<FeatureSummary>,
}

pub fn summarize_profiles(path: &Path) -> Result<ProfileSummary> {
    let profiles = read_profiles(path)?;
    let holders: BTreeSet<_> = profiles.iter().map(|profile| &profile.holder).collect();
    let features = ALL_FEATURES
        .into_iter()
        .map(|feature| {
            let methods = profiles
                .iter()
                .filter(|profile| profile.has_feature(feature))
                .count();
            FeatureSummary {
                feature,
                methods,
                percent: methods as f64 * 100.0 / profiles.len() as f64,
            }
        })
        .collect();
    Ok(ProfileSummary {
        schema_version: CORPUS_SCHEMA_VERSION,
        methods: profiles.len(),
        holders: holders.len(),
        features,
    })
}

#[derive(Clone, Debug, Deserialize, Serialize, PartialEq, Eq)]
#[serde(deny_unknown_fields)]
pub struct CorpusSelectionConfig {
    pub schema_version: u32,
    pub corpus_id: String,
    pub target_methods: usize,
    #[serde(default)]
    pub max_partial_methods: Option<usize>,
    #[serde(default)]
    pub seed: String,
    #[serde(default)]
    pub retain_all: Vec<RareFeature>,
    #[serde(default)]
    pub retain_at_least_one: Vec<RareFeature>,
    #[serde(default)]
    pub priorities: CorpusPriorities,
    #[serde(default)]
    pub buckets: CorpusBuckets,
}

#[derive(Clone, Debug, Default, Deserialize, Serialize, PartialEq, Eq)]
#[serde(deny_unknown_fields)]
pub struct CorpusPriorities {
    #[serde(default)]
    pub allocator_wall: usize,
    #[serde(default)]
    pub allocator_cpu: usize,
    #[serde(default)]
    pub peak_active: usize,
    #[serde(default)]
    pub peak_inactive: usize,
    #[serde(default)]
    pub register_pressure: usize,
}

#[derive(Clone, Debug, Deserialize, Serialize, PartialEq, Eq)]
#[serde(deny_unknown_fields)]
pub struct CorpusBuckets {
    #[serde(default = "default_pressure_buckets")]
    pub register_pressure: Vec<u32>,
    #[serde(default = "default_phi_buckets")]
    pub phi_count: Vec<u32>,
    #[serde(default = "default_interval_buckets")]
    pub live_intervals: Vec<u32>,
    #[serde(default = "default_instruction_buckets")]
    pub instructions: Vec<u32>,
}

impl Default for CorpusBuckets {
    fn default() -> Self {
        Self {
            register_pressure: default_pressure_buckets(),
            phi_count: default_phi_buckets(),
            live_intervals: default_interval_buckets(),
            instructions: default_instruction_buckets(),
        }
    }
}

fn default_pressure_buckets() -> Vec<u32> {
    vec![4, 8, 16, 32, 64, 128]
}

fn default_phi_buckets() -> Vec<u32> {
    vec![0, 1, 4, 16, 64]
}

fn default_interval_buckets() -> Vec<u32> {
    vec![8, 32, 128, 512, 2048]
}

fn default_instruction_buckets() -> Vec<u32> {
    vec![16, 64, 256, 1024, 4096]
}

impl CorpusSelectionConfig {
    pub fn from_toml_file(path: &Path) -> Result<Self> {
        let contents = fs::read_to_string(path)
            .with_context(|| format!("failed to read {}", path.display()))?;
        let config: Self = toml::from_str(&contents)
            .with_context(|| format!("failed to parse {}", path.display()))?;
        config.validate()?;
        Ok(config)
    }

    fn validate(&self) -> Result<()> {
        ensure!(
            self.schema_version == CORPUS_SCHEMA_VERSION,
            "unsupported corpus config schema {}",
            self.schema_version
        );
        ensure!(!self.corpus_id.is_empty(), "corpus_id must not be empty");
        ensure!(self.target_methods > 0, "target_methods must be positive");
        if let Some(max_partial_methods) = self.max_partial_methods {
            ensure!(
                max_partial_methods >= self.target_methods,
                "max_partial_methods must not be smaller than target_methods"
            );
        }
        ensure_sorted_unique(&self.buckets.register_pressure, "register_pressure")?;
        ensure_sorted_unique(&self.buckets.phi_count, "phi_count")?;
        ensure_sorted_unique(&self.buckets.live_intervals, "live_intervals")?;
        ensure_sorted_unique(&self.buckets.instructions, "instructions")?;
        Ok(())
    }
}

fn ensure_sorted_unique(values: &[u32], name: &str) -> Result<()> {
    ensure!(!values.is_empty(), "{name} buckets must not be empty");
    ensure!(
        values.windows(2).all(|window| window[0] < window[1]),
        "{name} buckets must be strictly increasing"
    );
    Ok(())
}

#[derive(Clone, Debug, Deserialize, Serialize, PartialEq, Eq, PartialOrd, Ord)]
pub struct StratumKey {
    register_pressure: usize,
    phi_count: usize,
    live_intervals: usize,
    instructions: usize,
    wide: bool,
    constrained: bool,
    spilled: bool,
    split: bool,
    invoke_range: bool,
    exception_cfg: bool,
}

#[derive(Clone, Debug, Deserialize, Serialize, PartialEq, Eq)]
pub struct StratumSummary {
    pub key: StratumKey,
    pub total: usize,
    pub selected: usize,
}

#[derive(Clone, Debug, Deserialize, Serialize, PartialEq, Eq)]
pub struct CorpusManifest {
    pub schema_version: u32,
    pub corpus_id: String,
    pub source_profile_sha256: String,
    pub source_methods: usize,
    pub target_methods: usize,
    pub selected_methods: usize,
    pub selected_rare_methods: usize,
    pub selected_forced_methods: usize,
    pub partial_methods: usize,
    #[serde(default)]
    pub source_allocator_wall_nanos: u64,
    #[serde(default)]
    pub selected_allocator_wall_nanos: u64,
    #[serde(default)]
    pub partial_allocator_wall_nanos: u64,
    #[serde(default)]
    pub source_allocator_cpu_nanos: u64,
    #[serde(default)]
    pub selected_allocator_cpu_nanos: u64,
    #[serde(default)]
    pub partial_allocator_cpu_nanos: u64,
    pub selected_method_ids: Vec<String>,
    pub selected_holders: Vec<String>,
    pub partial_include_patterns: Vec<String>,
    pub strata: Vec<StratumSummary>,
}

impl CorpusManifest {
    pub fn verify(&self, profile_path: &Path) -> Result<()> {
        ensure!(
            self.schema_version == CORPUS_SCHEMA_VERSION,
            "unsupported corpus manifest schema {}",
            self.schema_version
        );
        ensure!(
            sha256_file(profile_path)? == self.source_profile_sha256,
            "allocator profile hash differs"
        );
        let profiles = read_profiles(profile_path)?;
        let known: BTreeSet<_> = profiles.iter().map(|profile| &profile.method_id).collect();
        ensure!(
            self.selected_method_ids.iter().all(|id| known.contains(id)),
            "manifest references a method absent from the source profile"
        );
        ensure!(
            self.selected_methods == self.selected_method_ids.len(),
            "selected method count differs"
        );
        let selected_holders: BTreeSet<_> = self.selected_holders.iter().collect();
        ensure!(
            self.partial_methods
                == profiles
                    .iter()
                    .filter(|profile| selected_holders.contains(&profile.holder))
                    .count(),
            "partial method count differs"
        );
        ensure!(
            self.selected_method_ids
                .windows(2)
                .all(|pair| pair[0] < pair[1]),
            "selected method ids are not sorted and unique"
        );
        ensure!(
            self.selected_holders
                .windows(2)
                .all(|pair| pair[0] < pair[1]),
            "selected holders are not sorted and unique"
        );
        Ok(())
    }
}

pub fn read_profiles(path: &Path) -> Result<Vec<AllocatorMethodProfile>> {
    let file =
        fs::File::open(path).with_context(|| format!("failed to open {}", path.display()))?;
    let mut profiles = Vec::new();
    let mut seen = BTreeSet::new();
    for (index, line) in BufReader::new(file).lines().enumerate() {
        let line = line.with_context(|| format!("failed to read line {}", index + 1))?;
        if line.trim().is_empty() {
            continue;
        }
        let profile: AllocatorMethodProfile = serde_json::from_str(&line)
            .with_context(|| format!("invalid allocator profile on line {}", index + 1))?;
        profile.validate()?;
        ensure!(
            seen.insert(profile.method_id.clone()),
            "duplicate allocator method profile {}",
            profile.method_id
        );
        profiles.push(profile);
    }
    ensure!(!profiles.is_empty(), "allocator profile is empty");
    Ok(profiles)
}

pub fn select_corpus(
    profile_path: &Path,
    config: &CorpusSelectionConfig,
) -> Result<CorpusManifest> {
    let profiles = read_profiles(profile_path)?;
    let retained: BTreeSet<_> = config.retain_all.iter().copied().collect();
    let mut rare_ids: BTreeSet<_> = profiles
        .iter()
        .filter(|profile| retained.iter().any(|feature| profile.has_feature(*feature)))
        .map(|profile| profile.method_id.clone())
        .collect();
    for feature in &config.retain_at_least_one {
        if let Some(profile) = profiles
            .iter()
            .filter(|profile| profile.has_feature(*feature))
            .max_by_key(|profile| {
                (
                    profile.allocator_wall_nanos,
                    profile.allocator_cpu_nanos,
                    profile.peak_active.saturating_add(profile.peak_inactive),
                    Reverse(&profile.method_id),
                )
            })
        {
            rare_ids.insert(profile.method_id.clone());
        }
    }
    let mut forced_ids = rare_ids.clone();
    forced_ids.extend(top_method_ids(
        &profiles,
        config.priorities.allocator_wall,
        |profile| profile.allocator_wall_nanos,
    ));
    forced_ids.extend(top_method_ids(
        &profiles,
        config.priorities.allocator_cpu,
        |profile| profile.allocator_cpu_nanos,
    ));
    forced_ids.extend(top_method_ids(
        &profiles,
        config.priorities.peak_active,
        |profile| u64::from(profile.peak_active),
    ));
    forced_ids.extend(top_method_ids(
        &profiles,
        config.priorities.peak_inactive,
        |profile| u64::from(profile.peak_inactive),
    ));
    forced_ids.extend(top_method_ids(
        &profiles,
        config.priorities.register_pressure,
        |profile| u64::from(profile.peak_active.saturating_add(profile.peak_inactive)),
    ));
    let mut forced = Vec::new();
    let mut common: BTreeMap<StratumKey, Vec<&AllocatorMethodProfile>> = BTreeMap::new();
    for profile in &profiles {
        if forced_ids.contains(&profile.method_id) {
            forced.push(profile);
        } else {
            common
                .entry(stratum(profile, &config.buckets))
                .or_default()
                .push(profile);
        }
    }

    let common_budget = config.target_methods.saturating_sub(forced.len());
    let quotas = proportional_quotas(
        common
            .iter()
            .map(|(key, values)| (key.clone(), values.len()))
            .collect(),
        common_budget,
    );
    let mut selected: Vec<&AllocatorMethodProfile> = forced.clone();
    for (key, values) in &mut common {
        values.sort_by_key(|profile| deterministic_rank(&config.seed, &profile.method_id));
        selected.extend(values.iter().take(*quotas.get(key).unwrap_or(&0)).copied());
    }
    selected.sort_by_key(|profile| &profile.method_id);

    let selected_ids: BTreeSet<_> = selected.iter().map(|profile| &profile.method_id).collect();
    let mut strata = BTreeMap::new();
    for profile in &profiles {
        let key = stratum(profile, &config.buckets);
        let entry = strata.entry(key).or_insert((0, 0));
        entry.0 += 1;
        if selected_ids.contains(&profile.method_id) {
            entry.1 += 1;
        }
    }
    let selected_holders: BTreeSet<_> = selected
        .iter()
        .map(|profile| profile.holder.clone())
        .collect();
    let partial_include_patterns = selected_holders
        .iter()
        .map(|holder| holder_to_java_type(holder))
        .collect::<Result<Vec<_>>>()?;
    let partial_methods = profiles
        .iter()
        .filter(|profile| selected_holders.contains(&profile.holder))
        .count();
    let sum_metric = |values: &[&AllocatorMethodProfile],
                      metric: fn(&AllocatorMethodProfile) -> u64| {
        values.iter().fold(0_u64, |total, profile| {
            total.saturating_add(metric(profile))
        })
    };
    let all_profiles = profiles.iter().collect::<Vec<_>>();
    let partial_profiles = profiles
        .iter()
        .filter(|profile| selected_holders.contains(&profile.holder))
        .collect::<Vec<_>>();
    if let Some(max_partial_methods) = config.max_partial_methods {
        ensure!(
            partial_methods <= max_partial_methods,
            "class expansion selected {partial_methods} methods, above max_partial_methods={max_partial_methods}"
        );
    }

    Ok(CorpusManifest {
        schema_version: CORPUS_SCHEMA_VERSION,
        corpus_id: config.corpus_id.clone(),
        source_profile_sha256: sha256_file(profile_path)?,
        source_methods: profiles.len(),
        target_methods: config.target_methods,
        selected_methods: selected.len(),
        selected_rare_methods: rare_ids.len(),
        selected_forced_methods: forced.len(),
        partial_methods,
        source_allocator_wall_nanos: sum_metric(&all_profiles, |profile| {
            profile.allocator_wall_nanos
        }),
        selected_allocator_wall_nanos: sum_metric(&selected, |profile| {
            profile.allocator_wall_nanos
        }),
        partial_allocator_wall_nanos: sum_metric(&partial_profiles, |profile| {
            profile.allocator_wall_nanos
        }),
        source_allocator_cpu_nanos: sum_metric(&all_profiles, |profile| {
            profile.allocator_cpu_nanos
        }),
        selected_allocator_cpu_nanos: sum_metric(&selected, |profile| profile.allocator_cpu_nanos),
        partial_allocator_cpu_nanos: sum_metric(&partial_profiles, |profile| {
            profile.allocator_cpu_nanos
        }),
        selected_method_ids: selected
            .iter()
            .map(|profile| profile.method_id.clone())
            .collect(),
        selected_holders: selected_holders.into_iter().collect(),
        partial_include_patterns,
        strata: strata
            .into_iter()
            .map(|(key, (total, selected))| StratumSummary {
                key,
                total,
                selected,
            })
            .collect(),
    })
}

fn top_method_ids(
    profiles: &[AllocatorMethodProfile],
    count: usize,
    score: impl Fn(&AllocatorMethodProfile) -> u64,
) -> Vec<String> {
    if count == 0 {
        return Vec::new();
    }
    // Profiles can contain hundreds of thousands of methods while priority sets are typically
    // only tens or hundreds of entries. Keep a bounded set instead of sorting the entire profile
    // once for every priority metric. The first entry is the worst retained rank: lower score,
    // then lexicographically larger method id.
    let mut top = BTreeSet::new();
    for profile in profiles {
        top.insert((score(profile), Reverse(profile.method_id.as_str())));
        if top.len() > count {
            top.pop_first();
        }
    }
    top.into_iter()
        .map(|(_, Reverse(method_id))| method_id.to_owned())
        .collect()
}

fn stratum(profile: &AllocatorMethodProfile, buckets: &CorpusBuckets) -> StratumKey {
    StratumKey {
        register_pressure: bucket(
            profile.peak_active.saturating_add(profile.peak_inactive),
            &buckets.register_pressure,
        ),
        phi_count: bucket(profile.phi_count, &buckets.phi_count),
        live_intervals: bucket(profile.live_interval_count, &buckets.live_intervals),
        instructions: bucket(profile.instruction_count, &buckets.instructions),
        wide: profile.wide_interval_count > 0,
        constrained: profile.constrained_interval_count > 0,
        spilled: profile.spill_count > 0,
        split: profile.split_count > 0,
        invoke_range: profile.invoke_range_count > 0,
        exception_cfg: profile.exception_block_count > 0,
    }
}

fn bucket(value: u32, bounds: &[u32]) -> usize {
    bounds.partition_point(|bound| value > *bound)
}

fn proportional_quotas(
    sizes: BTreeMap<StratumKey, usize>,
    requested_budget: usize,
) -> BTreeMap<StratumKey, usize> {
    let total: usize = sizes.values().sum();
    let budget = requested_budget.min(total);
    let mut quotas: BTreeMap<_, _> = sizes.keys().cloned().map(|key| (key, 0)).collect();
    if budget == 0 || total == 0 {
        return quotas;
    }

    let mut remaining = budget;
    if budget >= sizes.len() {
        for (key, size) in &sizes {
            if *size > 0 {
                quotas.insert(key.clone(), 1);
                remaining -= 1;
            }
        }
    }
    let residual_total: usize = sizes
        .iter()
        .map(|(key, size)| size - quotas.get(key).copied().unwrap_or(0))
        .sum();
    if remaining == 0 || residual_total == 0 {
        return quotas;
    }

    let mut remainders = Vec::new();
    let initial_remaining = remaining;
    for (key, size) in &sizes {
        let available = size - quotas.get(key).copied().unwrap_or(0);
        let numerator = available * initial_remaining;
        let additional = numerator / residual_total;
        *quotas.get_mut(key).unwrap() += additional;
        remaining -= additional;
        remainders.push((numerator % residual_total, key.clone()));
    }
    remainders.sort_by_key(|(fraction, key)| (Reverse(*fraction), key.clone()));
    for (_, key) in remainders {
        if remaining == 0 {
            break;
        }
        if quotas[&key] < sizes[&key] {
            *quotas.get_mut(&key).unwrap() += 1;
            remaining -= 1;
        }
    }
    quotas
}

fn deterministic_rank(seed: &str, method_id: &str) -> [u8; 32] {
    let mut hasher = Sha256::new();
    hasher.update(seed.as_bytes());
    hasher.update([0]);
    hasher.update(method_id.as_bytes());
    hasher.finalize().into()
}

fn holder_to_java_type(holder: &str) -> Result<String> {
    let body = holder
        .strip_prefix('L')
        .and_then(|value| value.strip_suffix(';'))
        .with_context(|| format!("invalid holder descriptor {holder}"))?;
    Ok(body.replace('/', "."))
}

pub fn write_corpus_outputs(
    manifest: &CorpusManifest,
    manifest_path: &Path,
    partial_patterns_path: &Path,
    holder_filter_path: Option<&Path>,
) -> Result<()> {
    write_json(manifest_path, manifest)?;
    if let Some(parent) = partial_patterns_path.parent() {
        fs::create_dir_all(parent)?;
    }
    fs::write(
        partial_patterns_path,
        format!("{}\n", manifest.partial_include_patterns.join(",")),
    )
    .with_context(|| format!("failed to write {}", partial_patterns_path.display()))?;
    if let Some(holder_filter_path) = holder_filter_path {
        if let Some(parent) = holder_filter_path.parent() {
            fs::create_dir_all(parent)?;
        }
        fs::write(
            holder_filter_path,
            format!("{}\n", manifest.selected_holders.join("\n")),
        )
        .with_context(|| format!("failed to write {}", holder_filter_path.display()))?;
    }
    Ok(())
}

#[derive(Clone, Copy, Debug, Deserialize, Serialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum ExpectedEffect {
    Regression,
    Improvement,
    Neutral,
    CorrectnessFailure,
}

#[derive(Clone, Debug, Deserialize, Serialize, PartialEq)]
#[serde(deny_unknown_fields)]
pub struct CalibrationExpectation {
    pub id: String,
    pub expected: ExpectedEffect,
    #[serde(default = "default_wall_metric")]
    pub metric: String,
    #[serde(default)]
    pub min_abs_delta_percent: f64,
    #[serde(default = "default_neutral_tolerance")]
    pub neutral_tolerance_percent: f64,
}

fn default_wall_metric() -> String {
    "wall".to_string()
}

fn default_neutral_tolerance() -> f64 {
    1.0
}

#[derive(Clone, Debug, Deserialize, Serialize, PartialEq)]
#[serde(deny_unknown_fields)]
pub struct CalibrationPlan {
    pub schema_version: u32,
    pub experiments: Vec<CalibrationExpectation>,
}

impl CalibrationPlan {
    pub fn from_toml_file(path: &Path) -> Result<Self> {
        let contents = fs::read_to_string(path)
            .with_context(|| format!("failed to read {}", path.display()))?;
        let plan: Self = toml::from_str(&contents)
            .with_context(|| format!("failed to parse {}", path.display()))?;
        ensure!(
            plan.schema_version == CORPUS_SCHEMA_VERSION,
            "unsupported calibration schema {}",
            plan.schema_version
        );
        ensure!(!plan.experiments.is_empty(), "calibration plan is empty");
        Ok(plan)
    }
}

#[derive(Clone, Debug, Deserialize, Serialize, PartialEq)]
#[serde(deny_unknown_fields)]
pub struct CalibrationObservation {
    pub id: String,
    pub metric: String,
    pub delta_percent: Option<f64>,
    pub correctness_ok: bool,
}

#[derive(Clone, Debug, Deserialize, Serialize, PartialEq)]
#[serde(deny_unknown_fields)]
pub struct CalibrationObservations {
    pub schema_version: u32,
    pub experiments: Vec<CalibrationObservation>,
}

#[derive(Clone, Debug, Deserialize, Serialize, PartialEq)]
pub struct CalibrationResult {
    pub id: String,
    pub passed: bool,
    pub reason: String,
}

#[derive(Clone, Debug, Deserialize, Serialize, PartialEq)]
pub struct CalibrationReport {
    pub schema_version: u32,
    pub passed: bool,
    pub results: Vec<CalibrationResult>,
}

pub fn calibrate(plan: &CalibrationPlan, observations_path: &Path) -> Result<CalibrationReport> {
    let observations: CalibrationObservations = serde_json::from_slice(
        &fs::read(observations_path)
            .with_context(|| format!("failed to read {}", observations_path.display()))?,
    )
    .with_context(|| format!("failed to parse {}", observations_path.display()))?;
    ensure!(
        observations.schema_version == CORPUS_SCHEMA_VERSION,
        "unsupported observation schema {}",
        observations.schema_version
    );
    let by_id: BTreeMap<_, _> = observations
        .experiments
        .iter()
        .map(|observation| (observation.id.as_str(), observation))
        .collect();
    let mut results = Vec::new();
    for expected in &plan.experiments {
        let Some(observed) = by_id.get(expected.id.as_str()) else {
            results.push(CalibrationResult {
                id: expected.id.clone(),
                passed: false,
                reason: "missing observation".to_string(),
            });
            continue;
        };
        let (passed, reason) = evaluate_expectation(expected, observed);
        results.push(CalibrationResult {
            id: expected.id.clone(),
            passed,
            reason,
        });
    }
    Ok(CalibrationReport {
        schema_version: CORPUS_SCHEMA_VERSION,
        passed: results.iter().all(|result| result.passed),
        results,
    })
}

fn evaluate_expectation(
    expected: &CalibrationExpectation,
    observed: &CalibrationObservation,
) -> (bool, String) {
    if expected.expected == ExpectedEffect::CorrectnessFailure {
        let passed = !observed.correctness_ok;
        return (
            passed,
            if passed {
                "reproduced correctness failure".to_string()
            } else {
                "expected correctness failure was not reproduced".to_string()
            },
        );
    }
    if !observed.correctness_ok {
        return (false, "unexpected correctness failure".to_string());
    }
    if observed.metric != expected.metric {
        return (
            false,
            format!(
                "metric differs: expected {}, observed {}",
                expected.metric, observed.metric
            ),
        );
    }
    let Some(delta) = observed.delta_percent else {
        return (false, "missing performance delta".to_string());
    };
    let passed = match expected.expected {
        ExpectedEffect::Regression => delta >= expected.min_abs_delta_percent,
        ExpectedEffect::Improvement => delta <= -expected.min_abs_delta_percent,
        ExpectedEffect::Neutral => delta.abs() <= expected.neutral_tolerance_percent,
        ExpectedEffect::CorrectnessFailure => unreachable!(),
    };
    let reason = if passed {
        format!("direction reproduced at {delta:+.4}%")
    } else {
        format!("direction changed or effect escaped tolerance: observed {delta:+.4}%")
    };
    (passed, reason)
}

pub fn require_calibration(report: &CalibrationReport) -> Result<()> {
    if report.passed {
        Ok(())
    } else {
        bail!("allocator corpus calibration failed")
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use tempfile::tempdir;

    fn profile(id: usize, pressure: u32, rare: bool) -> AllocatorMethodProfile {
        AllocatorMethodProfile {
            schema_version: 1,
            method_id: format!("{id:064x}"),
            holder: format!("Lsample/C{id};"),
            method: format!("Lsample/C{id};->m()V"),
            instruction_count: pressure * 4 + 1,
            block_count: 1,
            phi_count: pressure / 8,
            live_interval_count: pressure * 2,
            wide_interval_count: u32::from(id.is_multiple_of(3)),
            constrained_interval_count: u32::from(rare),
            peak_active: pressure,
            peak_inactive: pressure / 2,
            register_count: pressure,
            spill_count: 0,
            split_count: 0,
            invoke_range_count: 0,
            exception_block_count: 0,
            allocator_wall_nanos: u64::from(pressure) * 1_000,
            allocator_cpu_nanos: u64::from(pressure) * 800,
        }
    }

    #[test]
    fn selection_is_deterministic_and_retains_rare_methods() {
        let root = tempdir().unwrap();
        let profiles = root.path().join("profiles.jsonl");
        let lines = (0..20)
            .map(|id| serde_json::to_string(&profile(id, id as u32 + 1, id == 0)).unwrap())
            .collect::<Vec<_>>()
            .join("\n");
        fs::write(&profiles, format!("{lines}\n")).unwrap();
        let config = CorpusSelectionConfig {
            schema_version: 1,
            corpus_id: "test".to_string(),
            target_methods: 7,
            max_partial_methods: None,
            seed: "fixed".to_string(),
            retain_all: vec![RareFeature::ConstrainedRegisters],
            retain_at_least_one: Vec::new(),
            priorities: CorpusPriorities {
                allocator_cpu: 1,
                ..CorpusPriorities::default()
            },
            buckets: CorpusBuckets::default(),
        };
        let first = select_corpus(&profiles, &config).unwrap();
        let second = select_corpus(&profiles, &config).unwrap();
        assert_eq!(first, second);
        assert_eq!(first.selected_methods, 7);
        assert!(first.selected_method_ids.contains(&format!("{:064x}", 0)));
        assert!(first.selected_method_ids.contains(&format!("{:064x}", 19)));
        assert!(first.selected_allocator_cpu_nanos > 0);
        assert!(first.partial_allocator_cpu_nanos >= first.selected_allocator_cpu_nanos);
        assert!(first.source_allocator_cpu_nanos >= first.partial_allocator_cpu_nanos);
        first.verify(&profiles).unwrap();
        let manifest = root.path().join("manifest.json");
        let patterns = root.path().join("patterns.txt");
        let holders = root.path().join("holders.txt");
        write_corpus_outputs(&first, &manifest, &patterns, Some(&holders)).unwrap();
        let written_holders = fs::read_to_string(holders).unwrap();
        assert_eq!(
            written_holders.lines().collect::<Vec<_>>(),
            first
                .selected_holders
                .iter()
                .map(String::as_str)
                .collect::<Vec<_>>()
        );
    }

    #[test]
    fn rare_methods_may_expand_the_target() {
        let root = tempdir().unwrap();
        let profiles = root.path().join("profiles.jsonl");
        let lines = (0..4)
            .map(|id| serde_json::to_string(&profile(id, 4, true)).unwrap())
            .collect::<Vec<_>>()
            .join("\n");
        fs::write(&profiles, format!("{lines}\n")).unwrap();
        let config = CorpusSelectionConfig {
            schema_version: 1,
            corpus_id: "test".to_string(),
            target_methods: 2,
            max_partial_methods: None,
            seed: String::new(),
            retain_all: vec![RareFeature::ConstrainedRegisters],
            retain_at_least_one: Vec::new(),
            priorities: CorpusPriorities::default(),
            buckets: CorpusBuckets::default(),
        };
        let manifest = select_corpus(&profiles, &config).unwrap();
        assert_eq!(manifest.selected_methods, 4);
        assert_eq!(manifest.selected_rare_methods, 4);
    }

    #[test]
    fn calibration_rejects_direction_changes() {
        let expected = CalibrationExpectation {
            id: "known-regression".to_string(),
            expected: ExpectedEffect::Regression,
            metric: "wall".to_string(),
            min_abs_delta_percent: 2.0,
            neutral_tolerance_percent: 1.0,
        };
        let observed = CalibrationObservation {
            id: expected.id.clone(),
            metric: "wall".to_string(),
            delta_percent: Some(-1.0),
            correctness_ok: true,
        };
        let (passed, reason) = evaluate_expectation(&expected, &observed);
        assert!(!passed);
        assert!(reason.contains("direction changed"));
    }
}
