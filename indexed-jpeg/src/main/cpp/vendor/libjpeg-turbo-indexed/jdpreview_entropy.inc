/* Included by jdhuff.c only. DC preview's fused AC skip table is separate
 * from the standard/indexed decoder. It never changes their bit buffer ABI.
 * Each entry skips as many complete AC symbols + amplitude bits as fit in
 * 12 bits. AC amplitudes are irrelevant to a DC-only block mean.
 */
#define DC_AC_BITS 12

/* Decoder-local fast reservoir refill. It does not modify the shared entropy
 * reader or its 32-bit tile checkpoint ABI. memcpy permits unaligned input.
 * If any byte might need JPEG FF/00 unstuffing (or signal a restart/EOI), use
 * the existing fully checked reader. Eight available bytes cover the read,
 * while only the six/seven actually needed bytes are committed. */
static INLINE int
dc_refill_word(bitread_working_state *reader, bit_buf_type *value, int *count)
{
#if BIT_BUF_SIZE == 64
  unsigned long long word, test;
  unsigned int bytes;
  if (*count >= DC_AC_BITS || reader->bytes_in_buffer < 8 ||
      reader->cinfo->unread_marker != 0) return 0;
  memcpy(&word, reader->next_input_byte, sizeof(word));
  test = word ^ 0xffffffffffffffffULL;
  if ((test - 0x0101010101010101ULL) & ~test & 0x8080808080808080ULL)
    return 0;
#if defined(__BYTE_ORDER__) && __BYTE_ORDER__ == __ORDER_LITTLE_ENDIAN__
  word = __builtin_bswap64(word);
#endif
  bytes = (56 - *count + 7) / 8;
  *value = (*value << (bytes * 8)) | (word >> ((8 - bytes) * 8));
  *count += bytes * 8;
  reader->next_input_byte += bytes;
  reader->bytes_in_buffer -= bytes;
  return 1;
#else
  return 0;
#endif
}

typedef struct {
  uint32_t *ac[D_MAX_BLOCKS_IN_MCU];
  boolean retain_ac[D_MAX_BLOCKS_IN_MCU];
} dc_entropy_tables;

GLOBAL(void *)
jpeg_dc_make_entropy_tables(j_decompress_ptr cinfo)
{
  huff_entropy_ptr entropy = (huff_entropy_ptr)cinfo->entropy;
  dc_entropy_tables *tables = (dc_entropy_tables *)
      (*cinfo->mem->alloc_small)((j_common_ptr)cinfo, JPOOL_IMAGE,
                               sizeof(dc_entropy_tables));
  uint32_t *unique[NUM_HUFF_TBLS] = {0};
  int b, ti;
  for (b = 0; b < cinfo->blocks_in_MCU; b++) {
    jpeg_component_info *comp = cinfo->cur_comp_info[cinfo->MCU_membership[b]];
    ti = comp->ac_tbl_no;
    if (!unique[ti]) {
      int look;
      d_derived_tbl *derived = entropy->ac_cur_tbls[b];
      unique[ti] = (uint32_t *)(*cinfo->mem->alloc_small)((j_common_ptr)cinfo,
          JPOOL_IMAGE, (1u << DC_AC_BITS) * sizeof(uint32_t));
      for (look = 0; look < (1 << DC_AC_BITS); look++) {
        int consumed = 0, advance = 0, terminal = 0;
        while (consumed < DC_AC_BITS) {
          int len, code = 0, symbol = -1, nbits, run;
          for (len = 1; consumed + len <= DC_AC_BITS; len++) {
            code = (code << 1) | ((look >> (DC_AC_BITS - consumed - len)) & 1);
            if (code <= derived->maxcode[len]) {
              symbol = derived->pub->huffval[code + derived->valoffset[len]];
              break;
            }
          }
          if (symbol < 0) break;
          nbits = symbol & 15; run = symbol >> 4;
          if (consumed + len + nbits > DC_AC_BITS) break;
          consumed += len + nbits;
          if (!nbits && run != 15) { terminal = 1; break; }
          advance += run + 1;
          if (advance > 63) break; // decoder will reject overrun
        }
        unique[ti][look] = (uint32_t)consumed | ((uint32_t)advance << 5) |
                             ((uint32_t)terminal << 13);
      }
    }
    tables->ac[b] = unique[ti];
    tables->retain_ac[b] = entropy->ac_needed[b];
  }
  return tables;
}

GLOBAL(boolean)
jpeg_dc_decode_mcu(j_decompress_ptr cinfo, JBLOCKROW *MCU_data, void *opaque)
{
  huff_entropy_ptr entropy = (huff_entropy_ptr)cinfo->entropy;
  dc_entropy_tables *tables = (dc_entropy_tables *)opaque;
  bit_buf_type get_buffer;
  int bits_left;
  bitread_working_state br_state;
  savable_state state;
  int b;
  if (cinfo->restart_interval && entropy->restarts_to_go == 0)
    if (!process_restart(cinfo)) return FALSE;
  if (entropy->pub.insufficient_data) ERREXIT(cinfo, JERR_INPUT_EOF);
  BITREAD_LOAD_STATE(cinfo, entropy->bitstate);
  state = entropy->saved;
  for (b = 0; b < cinfo->blocks_in_MCU; b++) {
    int s, k, r, ci;
    d_derived_tbl *dctbl = entropy->dc_cur_tbls[b];
    d_derived_tbl *actbl = entropy->ac_cur_tbls[b];
    HUFF_DECODE(s, br_state, dctbl, return FALSE, dc_slow);
    if (s) {
      CHECK_BIT_BUFFER(br_state, s, return FALSE);
      r = GET_BITS(s); s = HUFF_EXTEND(r, s);
    }
    ci = cinfo->MCU_membership[b];
    s += state.last_dc_val[ci];
    if (s < -32768 || s > 32767) ERREXIT(cinfo, JERR_BAD_DCT_COEF);
    state.last_dc_val[ci] = s;
    (*MCU_data[b])[0] = (JCOEF)s;
    // At standard 1/8 output, subsampled chroma can need a 2x2 or 4x4 IDCT.
    // Keep its real AC values. Discarding them would silently reduce color
    // detail on common 4:2:0/4:2:2/4:1:1 photos.
    if (tables->retain_ac[b]) {
      for (k = 1; k < DCTSIZE2; k++) {
        if (bits_left < DC_AC_BITS)
          dc_refill_word(&br_state, &get_buffer, &bits_left);
        HUFF_DECODE(s, br_state, actbl, return FALSE, retained_ac_slow);
        r = s >> 4; s &= 15;
        if (s) {
          k += r;
          if (k >= DCTSIZE2) ERREXIT(cinfo, JERR_BAD_HUFF_TABLE);
          CHECK_BIT_BUFFER(br_state, s, return FALSE);
          r = GET_BITS(s); s = HUFF_EXTEND(r, s);
          (*MCU_data[b])[jpeg_natural_order[k]] = (JCOEF)s;
        } else {
          if (r != 15) break;
          k += 15;
          if (k >= DCTSIZE2) ERREXIT(cinfo, JERR_BAD_HUFF_TABLE);
        }
      }
      continue;
    }
    k = 1;
    while (k < DCTSIZE2) {
      uint32_t entry = 0;
      if (bits_left < DC_AC_BITS) {
        if (!dc_refill_word(&br_state, &get_buffer, &bits_left)) {
          if (!jpeg_fill_bit_buffer(&br_state, get_buffer, bits_left, 0)) return FALSE;
          get_buffer = br_state.get_buffer; bits_left = br_state.bits_left;
        }
      }
      if (bits_left >= DC_AC_BITS) entry = tables->ac[b][PEEK_BITS(DC_AC_BITS)];
      /* A fused entry must stop strictly BEFORE coefficient 64. If it
       * reaches the block boundary, its following bits may belong to the
       * NEXT block's DC; decode one AC symbol normally in that case. */
      if ((entry & 31) && (((entry >> 5) & 255) < DCTSIZE2 - k)) {
        int skip = entry & 31;
        DROP_BITS(skip);
        k += (entry >> 5) & 255;
        if (k > DCTSIZE2) ERREXIT(cinfo, JERR_BAD_HUFF_TABLE);
        if (entry & (1u << 13)) break;
      } else {
        HUFF_DECODE(s, br_state, actbl, return FALSE, ac_slow);
        r = s >> 4; s &= 15;
        if (s) {
          k += r + 1;
          CHECK_BIT_BUFFER(br_state, s, return FALSE); DROP_BITS(s);
        } else {
          if (r != 15) break;
          k += 16;
        }
        if (k > DCTSIZE2) ERREXIT(cinfo, JERR_BAD_HUFF_TABLE);
      }
    }
  }
  BITREAD_SAVE_STATE(cinfo, entropy->bitstate);
  entropy->saved = state;
  if (cinfo->restart_interval) entropy->restarts_to_go--;
  return TRUE;
}

/* Wrap the public entropy interface, leaving huff_entropy_decoder untouched.
 * Each decompressor owns its adapter and lookup tables through JPOOL_IMAGE.
 * Restore the underlying pointer during a call because the existing Huffman
 * helpers intentionally cast cinfo->entropy to their private structure.
 */
typedef struct {
  struct jpeg_entropy_decoder pub;
  struct jpeg_entropy_decoder *original;
  void *tables;
} dc_scanline_adapter;

static boolean dc_scanline_mcu(j_decompress_ptr cinfo, JBLOCKROW *mcu)
{
  dc_scanline_adapter *adapter = (dc_scanline_adapter *)cinfo->entropy;
  boolean result;
  cinfo->entropy = adapter->original;
  result = jpeg_dc_decode_mcu(cinfo, mcu, adapter->tables);
  adapter->pub.insufficient_data = cinfo->entropy->insufficient_data;
  cinfo->entropy = &adapter->pub;
  return result;
}

static void dc_scanline_start_pass(j_decompress_ptr cinfo)
{
  dc_scanline_adapter *adapter = (dc_scanline_adapter *)cinfo->entropy;
  cinfo->entropy = adapter->original;
  (*cinfo->entropy->start_pass)(cinfo);
  adapter->tables = jpeg_dc_make_entropy_tables(cinfo);
  adapter->pub.insufficient_data = cinfo->entropy->insufficient_data;
  cinfo->entropy = &adapter->pub;
}

GLOBAL(boolean)
jpeg_dc_enable_fast_scanlines(j_decompress_ptr cinfo)
{
  dc_scanline_adapter *adapter;
  int ci, dc_components = 0;
  if (cinfo->global_state != DSTATE_SCANNING || cinfo->output_scanline != 0 ||
      cinfo->input_iMCU_row != 0 || cinfo->data_precision != 8 ||
      cinfo->progressive_mode || cinfo->arith_code || cinfo->raw_data_out ||
      cinfo->comps_in_scan != cinfo->num_components ||
      cinfo->Ss != 0 || cinfo->Se != 63 || cinfo->Ah != 0 || cinfo->Al != 0 ||
      !((cinfo->num_components == 1 && cinfo->jpeg_color_space == JCS_GRAYSCALE) ||
        (cinfo->num_components == 3 && (cinfo->jpeg_color_space == JCS_YCbCr ||
                                      cinfo->jpeg_color_space == JCS_RGB))))
    return FALSE;
  for (ci = 0; ci < cinfo->num_components; ci++)
    if (cinfo->comp_info[ci]._DCT_scaled_size == 1) dc_components++;
  if (!dc_components) return FALSE;
  if (cinfo->entropy->decode_mcu == dc_scanline_mcu) return FALSE;
  adapter = (dc_scanline_adapter *)(*cinfo->mem->alloc_small)(
      (j_common_ptr)cinfo, JPOOL_IMAGE, sizeof(dc_scanline_adapter));
  adapter->pub = *cinfo->entropy;
  adapter->original = cinfo->entropy;
  adapter->tables = jpeg_dc_make_entropy_tables(cinfo);
  adapter->pub.decode_mcu = dc_scanline_mcu;
  adapter->pub.start_pass = dc_scanline_start_pass;
  cinfo->entropy = &adapter->pub;
  return TRUE;
}
