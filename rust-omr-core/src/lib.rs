//! High-Speed Rust Optical Mark Recognition (OMR) Core (`rust-omr-core`)
//! Zero-allocation 5-stage computer vision pipeline for 600x780 exam sheets:
//! 1. 32-bit Integral-Image Local Adaptive Luminance Normalization
//! 2. 3x3 Seed-Lattice BFS Connected-Component 6-Fiducial Detector (TL, TR, ML, MR, BL, BR)
//! 3. Direct Inverse Piecewise-Bilinear Dual-Quad Local-Jacobian Disc Sampler
//! 4. Automatic Form Topology Classifier (Form 922412 [10Q] vs Form 6353B [16Q]) & 7-Digit ID Decoder
//! 5. 2-Tier Relative + Absolute Row Contrast Bubble Classifier

pub const CANON_W: usize = 600;
pub const CANON_H: usize = 780;
pub const TOTAL_PX: usize = CANON_W * CANON_H;

pub const CANON_TL: (f32, f32) = (64.0, 64.0);
pub const CANON_TR: (f32, f32) = (536.0, 64.0);
pub const CANON_ML: (f32, f32) = (64.0, 390.0);
pub const CANON_MR: (f32, f32) = (536.0, 390.0);
pub const CANON_BL: (f32, f32) = (64.0, 716.0);
pub const CANON_BR: (f32, f32) = (536.0, 716.0);

#[derive(Clone, Copy, Debug, Default)]
pub struct Pt2D {
    pub x: f32,
    pub y: f32,
    pub fill: f32,
}

#[derive(Clone, Copy, Debug, Default)]
pub struct Fiducials6 {
    pub tl: Pt2D,
    pub tr: Pt2D,
    pub ml: Pt2D,
    pub mr: Pt2D,
    pub bl: Pt2D,
    pub br: Pt2D,
    pub locked: bool,
}

#[inline(always)]
pub fn map_canonical_to_source(cx: f32, cy: f32, f: &Fiducials6) -> (f32, f32) {
    let is_upper = cy <= CANON_ML.1;
    let (u, v, q_tl, q_tr, q_bl, q_br) = if is_upper {
        (
            (cx - CANON_TL.0) / (CANON_TR.0 - CANON_TL.0),
            (cy - CANON_TL.1) / (CANON_ML.1 - CANON_TL.1),
            f.tl,
            f.tr,
            f.ml,
            f.mr,
        )
    } else {
        (
            (cx - CANON_ML.0) / (CANON_MR.0 - CANON_ML.0),
            (cy - CANON_ML.1) / (CANON_BL.1 - CANON_ML.1),
            f.ml,
            f.mr,
            f.bl,
            f.br,
        )
    };
    let top_x = q_tl.x + u * (q_tr.x - q_tl.x);
    let top_y = q_tl.y + u * (q_tr.y - q_tl.y);
    let bot_x = q_bl.x + u * (q_br.x - q_bl.x);
    let bot_y = q_bl.y + u * (q_br.y - q_bl.y);
    (top_x + v * (bot_x - top_x), top_y + v * (bot_y - top_y))
}

pub fn compute_adaptive_darkness(luma: &[u8; TOTAL_PX], integral: &mut [i32], darkness: &mut [f32; TOTAL_PX]) {
    let stride = CANON_W + 1;
    for y in 0..CANON_H {
        let mut row_sum: i32 = 0;
        let row_off = y * CANON_W;
        let prev_int = y * stride;
        let cur_int = (y + 1) * stride;
        for x in 0..CANON_W {
            row_sum += luma[row_off + x] as i32;
            integral[cur_int + x + 1] = integral[prev_int + x + 1] + row_sum;
        }
    }

    let rad: usize = 24;
    for y in 0..CANON_H {
        let y0 = y.saturating_sub(rad);
        let y1 = (y + rad).min(CANON_H - 1);
        let h_span = (y1 - y0 + 1) as i32;
        let r0 = y0 * stride;
        let r1 = (y1 + 1) * stride;
        let row_off = y * CANON_W;

        for x in 0..CANON_W {
            let x0 = x.saturating_sub(rad);
            let x1 = (x + rad).min(CANON_W - 1);
            let area = ((x1 - x0 + 1) as i32) * h_span;
            let sum = integral[r1 + x1 + 1] - integral[r0 + x1 + 1] - integral[r1 + x0] + integral[r0 + x0];
            let lum = luma[row_off + x] as i32;
            let diff_area = sum - (lum + 10) * area;
            if diff_area <= 0 {
                darkness[row_off + x] = 0.0;
            } else {
                let mean = (sum as f32) / (area as f32);
                let d = (mean - (lum as f32) - 10.0) / (mean * 0.52).max(34.0);
                darkness[row_off + x] = d.min(1.0);
            }
        }
    }
}
