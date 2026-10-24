//! Photo → hand-drawn pencil sketch.
//!
//! The classic "colour dodge" trick artists use in photo editors:
//!
//! 1. Make the photo grey.
//! 2. Make a blurred negative of it.
//! 3. Divide one by the other. Flat areas cancel out to white paper; edges, where the
//!    blur and the original disagree, stay dark — they become pencil strokes.
//!
//! The result is an **ink mask**: every pixel says how much ink is there (0 = paper,
//! 255 = full ink). It has no colour on purpose. The phone paints the ink and paper with
//! whatever Material 3 colours the user picks, so re-colouring is instant and never
//! needs another job.

use image::{DynamicImage, GrayAlphaImage, LumaA, imageops};

/// How strongly strokes are darkened. Higher = bolder, more "marker"-like lines.
const INK_BOOST: f32 = 1.8;

/// Turns a photo into an ink mask: white pixels whose alpha is the amount of ink.
pub fn pencil(img: &DynamicImage) -> GrayAlphaImage {
    let gray = img.to_luma8();
    let (width, height) = gray.dimensions();

    let mut negative = gray.clone();
    imageops::invert(&mut negative);
    // Blur radius scales with the picture so small and large photos look alike.
    let sigma = (width.max(height) as f32 / 150.0).max(2.0);
    let blurred = imageops::fast_blur(&negative, sigma);

    let mut mask = GrayAlphaImage::new(width, height);
    for (x, y, pixel) in mask.enumerate_pixels_mut() {
        let base = f32::from(gray.get_pixel(x, y)[0]);
        let blur = f32::from(blurred.get_pixel(x, y)[0]);
        // Colour dodge: base / (1 - blur), on a 0-255 scale.
        let dodge = if blur >= 255.0 { 255.0 } else { (base * 255.0 / (255.0 - blur)).min(255.0) };
        let ink = ((255.0 - dodge) / 255.0 * INK_BOOST).min(1.0);
        *pixel = LumaA([255, (ink * 255.0).round() as u8]);
    }
    mask
}

/// Flattens the ink mask onto paper in two colours — only used for laptop previews.
pub fn render(mask: &GrayAlphaImage, ink: [u8; 3], paper: [u8; 3]) -> image::RgbImage {
    image::RgbImage::from_fn(mask.width(), mask.height(), |x, y| {
        let amount = f32::from(mask.get_pixel(x, y)[1]) / 255.0;
        let mix = |i: usize| (f32::from(paper[i]) + (f32::from(ink[i]) - f32::from(paper[i])) * amount).round() as u8;
        image::Rgb([mix(0), mix(1), mix(2)])
    })
}
