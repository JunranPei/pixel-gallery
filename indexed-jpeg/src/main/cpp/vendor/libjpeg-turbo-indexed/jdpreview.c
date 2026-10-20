/* Pixel Gallery DC preview extension.
 * Decode every entropy-coded symbol, but retain DC only for ALL components.
 * Accumulate block means directly into the final 1/16 or 1/32 component rows.
 * No full 1/8 RGB image, chroma IDCT/upsampling, or full coefficient index.
 * This deliberately does NOT claim to skip the compressed entropy stream.
 */
#define JPEG_INTERNALS
#include "jinclude.h"
#include "jpeglib.h"
#include "jpegcomp.h"
#include "jdpreview.h"
#include <stdint.h>

/* Specialized entropy reader, defined alongside the standard Huffman reader. */
EXTERN(void *) jpeg_dc_make_entropy_tables(j_decompress_ptr cinfo);
EXTERN(boolean) jpeg_dc_decode_mcu(j_decompress_ptr cinfo, JBLOCKROW *mcu, void *tables);

typedef struct {
  int component, x, y, width, height;
  unsigned int quant;
} dc_block;

static int clamp8(int value)
{
  return value < 0 ? 0 : (value > 255 ? 255 : value);
}

GLOBAL(boolean)
jpeg_dc_rgba8_supported(j_decompress_ptr cinfo)
{
  JDIMENSION w, h;
  int ci;
  if (cinfo->max_h_samp_factor != 1 || cinfo->max_v_samp_factor != 1 ||
      !jpeg_dc_preview_dimensions(cinfo, 16, &w, &h)) return FALSE;
  for (ci = 0; ci < cinfo->num_components; ci++) {
    JQUANT_TBL *quant = cinfo->quant_tbl_ptrs[cinfo->comp_info[ci].quant_tbl_no];
    if (!quant || quant->quantval[0] == 0 || quant->quantval[0] > 255) return FALSE;
  }
  return TRUE;
}

GLOBAL(jpeg_dc_result)
jpeg_read_dc_rgba8(j_decompress_ptr cinfo, unsigned char *rgba, size_t stride,
                   jpeg_dc_continue should_continue, void *context)
{
  JBLOCK blocks[D_MAX_BLOCKS_IN_MCU];
  JBLOCKROW mcu[D_MAX_BLOCKS_IN_MCU];
  unsigned int quant[3];
  int component[3], ci, b, ycc;
  JDIMENSION row, col;
  void *tables;
  JSAMPLE *range_limit;
  if (!jpeg_dc_rgba8_supported(cinfo)) return JPEG_DC_UNSUPPORTED;
  if (!rgba || stride < (size_t)((cinfo->image_width + 7) / 8) * 4 ||
      stride > SIZE_MAX / ((cinfo->image_height + 7) / 8))
    ERREXIT(cinfo, JERR_BAD_BUFFER_MODE);
  if (should_continue && !should_continue(context)) {
    jpeg_abort_decompress(cinfo); return JPEG_DC_CANCELLED;
  }
  cinfo->scale_num = 1; cinfo->scale_denom = 8;
  cinfo->raw_data_out = TRUE; cinfo->do_fancy_upsampling = FALSE;
  ycc = cinfo->jpeg_color_space == JCS_YCbCr;
  if (!jpeg_start_decompress(cinfo)) ERREXIT(cinfo, JERR_CANT_SUSPEND);
  range_limit = cinfo->sample_range_limit + CENTERJSAMPLE;
  if (cinfo->blocks_in_MCU != cinfo->num_components) ERREXIT(cinfo, JERR_BAD_MCU_SIZE);
  for (ci = 0; ci < cinfo->num_components; ci++) {
    cinfo->comp_info[ci]._DCT_scaled_size = 1;
    quant[ci] = cinfo->cur_comp_info[ci]->quant_table->quantval[0];
    component[ci] = cinfo->cur_comp_info[ci]->component_index;
  }
  (*cinfo->entropy->start_pass)(cinfo);
  tables = jpeg_dc_make_entropy_tables(cinfo);
  memset(blocks, 0, sizeof(blocks));
  for (b = 0; b < D_MAX_BLOCKS_IN_MCU; b++) mcu[b] = &blocks[b];
  for (row = 0; row < cinfo->MCU_rows_in_scan; row++) {
    unsigned char *out = rgba + row * stride;
    for (col = 0; col < cinfo->MCUs_per_row; col++) {
      int v[3] = {0, 0, 0};
      if ((col & 511) == 0 && should_continue && !should_continue(context)) {
        jpeg_abort_decompress(cinfo); return JPEG_DC_CANCELLED;
      }
      if (!jpeg_dc_decode_mcu(cinfo, mcu, tables)) ERREXIT(cinfo, JERR_CANT_SUSPEND);
      if (cinfo->entropy->insufficient_data) ERREXIT(cinfo, JERR_INPUT_EOF);
      for (ci = 0; ci < cinfo->num_components; ci++) {
        int64_t dc = (int64_t)blocks[ci][0] * quant[ci] + 4;
        /* Match the standard 1x1 IDCT range table, including extreme DC. */
        int value = (int)(dc >= 0 ? dc / 8 : -((-dc + 7) / 8));
        v[component[ci]] = range_limit[value & (MAXJSAMPLE * 4 + 3)];
      }
      if (cinfo->num_components == 1) out[0] = out[1] = out[2] = (unsigned char)v[0];
      else if (ycc) {
        int cb = v[1] - 128, cr = v[2] - 128;
        out[0] = (unsigned char)clamp8(v[0] + ((91881 * cr + 32768) >> 16));
        out[1] = (unsigned char)clamp8(v[0] + ((-22554 * cb - 46802 * cr + 32768) >> 16));
        out[2] = (unsigned char)clamp8(v[0] + ((116130 * cb + 32768) >> 16));
      } else { out[0] = (unsigned char)v[0]; out[1] = (unsigned char)v[1]; out[2] = (unsigned char)v[2]; }
      out[3] = 255; out += 4;
    }
  }
  (*cinfo->inputctl->finish_input_pass)(cinfo);
  while (!cinfo->inputctl->eoi_reached)
    if ((*cinfo->inputctl->consume_input)(cinfo) == JPEG_SUSPENDED) ERREXIT(cinfo, JERR_CANT_SUSPEND);
  if (should_continue && !should_continue(context)) {
    jpeg_abort_decompress(cinfo); return JPEG_DC_CANCELLED;
  }
  (*cinfo->src->term_source)(cinfo);
  jpeg_abort_decompress(cinfo);
  return JPEG_DC_OK;
}

GLOBAL(boolean)
jpeg_dc_preview_dimensions(j_decompress_ptr cinfo, unsigned int sample,
                           JDIMENSION *width, JDIMENSION *height)
{
  int ci;
  if (cinfo->global_state != DSTATE_READY || (sample != 16 && sample != 32) ||
      cinfo->data_precision != 8 || cinfo->progressive_mode || cinfo->arith_code ||
      cinfo->comps_in_scan != cinfo->num_components ||
      cinfo->Ss != 0 || cinfo->Se != 63 || cinfo->Ah != 0 || cinfo->Al != 0 ||
      !((cinfo->num_components == 1 && cinfo->jpeg_color_space == JCS_GRAYSCALE) ||
        (cinfo->num_components == 3 && (cinfo->jpeg_color_space == JCS_YCbCr ||
                                      cinfo->jpeg_color_space == JCS_RGB))))
    return FALSE;
  if (cinfo->max_h_samp_factor > 2 || cinfo->max_v_samp_factor > 2)
    return FALSE;
  if (cinfo->num_components == 1 &&
      (cinfo->max_h_samp_factor != 1 || cinfo->max_v_samp_factor != 1))
    return FALSE;
  for (ci = 0; ci < cinfo->num_components; ci++) {
    jpeg_component_info *comp = &cinfo->comp_info[ci];
    if (comp->h_samp_factor < 1 || comp->v_samp_factor < 1 ||
        cinfo->max_h_samp_factor % comp->h_samp_factor ||
        cinfo->max_v_samp_factor % comp->v_samp_factor)
      return FALSE;
  }
  *width = (cinfo->image_width + sample - 1) / sample;
  *height = (cinfo->image_height + sample - 1) / sample;
  return TRUE;
}

GLOBAL(jpeg_dc_result)
jpeg_read_dc_preview(j_decompress_ptr cinfo, unsigned int sample,
                     unsigned char *rgb, size_t stride,
                     jpeg_dc_continue should_continue, void *context)
{
  JDIMENSION width, height, row, col;
  JBLOCK blocks[D_MAX_BLOCKS_IN_MCU];
  JBLOCKROW mcu[D_MAX_BLOCKS_IN_MCU];
  dc_block layout[D_MAX_BLOCKS_IN_MCU];
  uint64_t *sums, *weights;
  int ci, bx, by, b, mcu_width, mcu_height, components, ycc;
  void *entropy_tables;

  if (!jpeg_dc_preview_dimensions(cinfo, sample, &width, &height))
    return JPEG_DC_UNSUPPORTED;
  if (rgb == NULL || stride < (size_t)width * 3 ||
      (height != 0 && stride > SIZE_MAX / height))
    ERREXIT(cinfo, JERR_BAD_BUFFER_MODE);
  if (should_continue && !should_continue(context)) {
    jpeg_abort_decompress(cinfo);
    return JPEG_DC_CANCELLED;
  }
  components = cinfo->num_components;
  ycc = cinfo->jpeg_color_space == JCS_YCbCr;
  cinfo->scale_num = 1;
  cinfo->scale_denom = 8;
  cinfo->raw_data_out = TRUE;
  cinfo->do_fancy_upsampling = FALSE;
  if (!jpeg_start_decompress(cinfo))
    ERREXIT(cinfo, JERR_CANT_SUSPEND);

  /* Standard 1/8 may retain 2x2 chroma IDCT blocks. Restart entropy before
   * ANY input MCU is consumed, with DC-only retention for every component. */
  for (ci = 0; ci < components; ci++)
    cinfo->comp_info[ci]._DCT_scaled_size = 1;
  (*cinfo->entropy->start_pass)(cinfo);
  entropy_tables = jpeg_dc_make_entropy_tables(cinfo);
  mcu_width = cinfo->max_h_samp_factor * DCTSIZE;
  mcu_height = cinfo->max_v_samp_factor * DCTSIZE;
  memset(blocks, 0, sizeof(blocks));
  for (b = 0; b < D_MAX_BLOCKS_IN_MCU; b++) mcu[b] = &blocks[b];
  b = 0;
  for (ci = 0; ci < cinfo->comps_in_scan; ci++) {
    jpeg_component_info *comp = cinfo->cur_comp_info[ci];
    int w = mcu_width / comp->h_samp_factor;
    int h = mcu_height / comp->v_samp_factor;
    for (by = 0; by < comp->MCU_height; by++) {
      for (bx = 0; bx < comp->MCU_width; bx++, b++) {
        layout[b].component = comp->component_index;
        layout[b].x = bx * w;
        layout[b].y = by * h;
        layout[b].width = w;
        layout[b].height = h;
        layout[b].quant = comp->quant_table->quantval[0];
      }
    }
  }
  if (b != cinfo->blocks_in_MCU) ERREXIT(cinfo, JERR_BAD_MCU_SIZE);
  sums = (uint64_t *)(*cinfo->mem->alloc_small)((j_common_ptr)cinfo,
      JPOOL_IMAGE, (size_t)width * components * sizeof(uint64_t));
  weights = (uint64_t *)(*cinfo->mem->alloc_small)((j_common_ptr)cinfo,
      JPOOL_IMAGE, (size_t)width * components * sizeof(uint64_t));

  for (row = 0; row < cinfo->MCU_rows_in_scan; row++) {
    unsigned int oy = row * mcu_height / sample;
    if (row * mcu_height % sample == 0) {
      memset(sums, 0, (size_t)width * components * sizeof(uint64_t));
      memset(weights, 0, (size_t)width * components * sizeof(uint64_t));
    }
    for (col = 0; col < cinfo->MCUs_per_row; col++) {
      unsigned int ox = col * mcu_width / sample;
      if ((col & 127) == 0 && should_continue && !should_continue(context)) {
        jpeg_abort_decompress(cinfo);
        return JPEG_DC_CANCELLED;
      }
      if (!jpeg_dc_decode_mcu(cinfo, mcu, entropy_tables))
        ERREXIT(cinfo, JERR_CANT_SUSPEND);
      if (cinfo->entropy->insufficient_data)
        ERREXIT(cinfo, JERR_INPUT_EOF);
      for (b = 0; b < cinfo->blocks_in_MCU; b++) {
        dc_block *entry = &layout[b];
        int x = col * mcu_width + entry->x;
        int y = row * mcu_height + entry->y;
        int w, h, value;
        size_t index;
        int64_t dc;
        uint64_t weight;
        if (x >= cinfo->image_width || y >= cinfo->image_height) continue;
        w = (int)cinfo->image_width - x;
        h = (int)cinfo->image_height - y;
        if (w > entry->width) w = entry->width;
        if (h > entry->height) h = entry->height;
        weight = (uint64_t)w * h;
        dc = (int64_t)blocks[b][0] * entry->quant + 4;
        value = clamp8((int)(dc >= 0 ? dc / 8 : -((-dc + 7) / 8)) + 128);
        index = (size_t)ox * components + entry->component;
        sums[index] += (uint64_t)value * weight;
        weights[index] += weight;
      }
    }
    if ((row + 1) * mcu_height % sample == 0 ||
        row + 1 == cinfo->MCU_rows_in_scan) {
      unsigned char *out = rgb + oy * stride;
      for (col = 0; col < width; col++) {
        int v[3] = {0, 0, 0};
        for (ci = 0; ci < components; ci++) {
          size_t index = (size_t)col * components + ci;
          if (!weights[index]) ERREXIT(cinfo, JERR_BAD_MCU_SIZE);
          v[ci] = (int)((sums[index] + weights[index] / 2) / weights[index]);
        }
        if (components == 1) {
          out[0] = out[1] = out[2] = (unsigned char)v[0];
        } else if (ycc) {
          int cb = v[1] - 128, cr = v[2] - 128;
          out[0] = (unsigned char)clamp8(v[0] + ((91881 * cr + 32768) >> 16));
          out[1] = (unsigned char)clamp8(v[0] +
              ((-22554 * cb - 46802 * cr + 32768) >> 16));
          out[2] = (unsigned char)clamp8(v[0] + ((116130 * cb + 32768) >> 16));
        } else {
          out[0] = (unsigned char)v[0];
          out[1] = (unsigned char)v[1];
          out[2] = (unsigned char)v[2];
        }
        out += 3;
      }
    }
  }
  (*cinfo->inputctl->finish_input_pass)(cinfo);
  /* The Android tile fork's finish_decompress omits EOI consumption. This
   * independent API verifies it explicitly and rejects additional scans. */
  while (!cinfo->inputctl->eoi_reached) {
    if ((*cinfo->inputctl->consume_input)(cinfo) == JPEG_SUSPENDED)
      ERREXIT(cinfo, JERR_CANT_SUSPEND);
  }
  if (should_continue && !should_continue(context)) {
    jpeg_abort_decompress(cinfo);
    return JPEG_DC_CANCELLED;
  }
  (*cinfo->src->term_source)(cinfo);
  jpeg_abort_decompress(cinfo);
  return JPEG_DC_OK;
}
