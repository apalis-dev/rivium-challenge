//! Photo → Material 3 colour schemes.
//!
//! Uses `material-colors`, a Rust port of Google's material-color-utilities — the same
//! algorithm Android uses to build Material You themes from your wallpaper:
//!
//! 1. Shrink the photo to ~128 px (quantizing is slow on millions of pixels).
//! 2. Quantize: group similar pixels into at most 128 colour clusters.
//! 3. Score: rank the clusters for how good a theme seed they make (vivid, common,
//!    distinct from each other). The top ones become the swatches in the app.
//! 4. Build a full light + dark scheme from the best seed, once per scheme variant, so the
//!    app can switch between Tonal / Vibrant / Expressive / ... without another job.

use std::collections::BTreeMap;

use image::{DynamicImage, imageops::FilterType};
use material_colors::{
    color::Rgb,
    dynamic_color::{Variant, color_spec::SpecVersion},
    quantize::{Quantizer, QuantizerCelebi}, // `quantize()` comes from the Quantizer trait
    scheme::Scheme,
    score::Score,
    theme::ThemeBuilder,
};
use serde::{Deserialize, Serialize};

/// Scheme variants offered in the app, with the key used in the JSON.
const VARIANTS: [(&str, Variant); 5] = [
    ("tonal_spot", Variant::TonalSpot),
    ("vibrant", Variant::Vibrant),
    ("expressive", Variant::Expressive),
    ("fidelity", Variant::Fidelity),
    ("monochrome", Variant::Monochrome),
];

/// Role name (`primary`, `on_surface`, ...) → `#rrggbb`.
pub type Roles = BTreeMap<String, String>;

#[derive(Debug, Serialize, Deserialize)]
pub struct SchemePair {
    pub light: Roles,
    pub dark: Roles,
}

#[derive(Debug, Serialize, Deserialize)]
pub struct Swatch {
    /// The seed colour, as `#rrggbb`.
    pub seed: String,
    /// Variant key → light and dark schemes generated from this seed.
    pub schemes: BTreeMap<String, SchemePair>,
}

#[derive(Debug, Serialize, Deserialize)]
pub struct Palette {
    /// Up to four swatches ranked best-first. The first is the default ink.
    pub swatches: Vec<Swatch>,
}

pub fn from_image(img: &DynamicImage) -> Palette {
    let small = img.resize(128, 128, FilterType::Triangle).to_rgb8();
    let pixels: Vec<Rgb> = small.pixels().map(|p| Rgb::new(p[0], p[1], p[2])).collect();

    let clusters = QuantizerCelebi::quantize(&pixels, 128);
    let seeds = Score::score(&clusters.color_to_count, Some(4), None, None);
    let swatches = seeds
        .iter()
        .map(|&seed| Swatch { seed: hex(&seed), schemes: schemes_for(seed) })
        .collect();
    Palette { swatches }
}

fn schemes_for(source: Rgb) -> BTreeMap<String, SchemePair> {
    VARIANTS
        .iter()
        .map(|&(key, variant)| {
            let theme = ThemeBuilder::with_source(source)
                .variant(variant)
                .spec_version(SpecVersion::Spec2025) // the M3 Expressive colour spec
                .build();
            let pair = SchemePair {
                light: roles(theme.schemes.light),
                dark: roles(theme.schemes.dark),
            };
            (key.to_owned(), pair)
        })
        .collect()
}

fn roles(scheme: Scheme) -> Roles {
    scheme.into_iter().map(|(name, color)| (name.to_owned(), hex(&color))).collect()
}

fn hex(color: &Rgb) -> String {
    format!("#{}", color.as_hex())
}

/// Parses `#rrggbb` back into bytes (used for laptop previews).
pub fn rgb(hex: &str) -> [u8; 3] {
    let value = u32::from_str_radix(hex.trim_start_matches('#'), 16).unwrap_or(0);
    [(value >> 16) as u8, (value >> 8) as u8, value as u8]
}
