/* Experimental, opt-in DC preview extension. Standard JPEG APIs are unchanged.
 * The caller owns error handling (as with jpeg_read_scanlines). No suspension.
 * Output is encoded-orientation RGB8, without ICC/EXIF transforms.
 */
#ifndef PIXEL_JDPREVIEW_H
#define PIXEL_JDPREVIEW_H

#ifdef __cplusplus
extern "C" {
#endif

typedef enum {
  JPEG_DC_OK = 0,
  JPEG_DC_UNSUPPORTED = 1,
  JPEG_DC_CANCELLED = 2
} jpeg_dc_result;

/* A zero return cancels. Called before reading and at least once per MCU row. */
typedef int (*jpeg_dc_continue)(void *context);

/* Opt-in replacement for the entropy stage of standard 1/8 scanline output.
 * Call AFTER jpeg_start_decompress, before any MCU is read. Only sequential
 * single-scan 8-bit grayscale/RGB/YCbCr with at least one DC-only component.
 * Subsampled chroma that needs AC coefficients keeps the original values.
 * Standard IDCT, color conversion, scanline output, and EOI handling remain.
 * FALSE leaves the decoder untouched. No change to the tile checkpoint ABI.
 */
EXTERN(boolean) jpeg_dc_enable_fast_scanlines(j_decompress_ptr cinfo);

/* Fused exact 1x1-IDCT output when EVERY component is DC-only (gray/RGB/444).
 * Other sampling layouts retain standard scanlines and their chroma AC.
 * Call before start_decompress. Output encoded orientation RGBA8, alpha 255.
 */
EXTERN(boolean) jpeg_dc_rgba8_supported(j_decompress_ptr cinfo);
EXTERN(jpeg_dc_result) jpeg_read_dc_rgba8(j_decompress_ptr cinfo,
    unsigned char *rgba, size_t stride, jpeg_dc_continue should_continue, void *context);

/* Call after jpeg_read_header, before start_decompress. sample = 16 or 32.
 * FALSE leaves the decompressor untouched, so a standard decoder can be used.
 */
EXTERN(boolean) jpeg_dc_preview_dimensions(j_decompress_ptr cinfo,
    unsigned int sample, JDIMENSION *width, JDIMENSION *height);

/* RGB buffer must hold stride * height bytes; stride >= width * 3.
 * Partial buffer contents must be discarded unless JPEG_DC_OK is returned.
 * Fatal input errors use cinfo->err, including truncated or additional scans.
 * Success consumes EOI and resets the decompressor. Cancellation aborts it.
 */
EXTERN(jpeg_dc_result) jpeg_read_dc_preview(j_decompress_ptr cinfo,
    unsigned int sample, unsigned char *rgb, size_t stride,
    jpeg_dc_continue should_continue, void *context);

#ifdef __cplusplus
}
#endif
#endif
