# Reproducible local extension to pinned upstream 3.1.3. No network or fuzzy patch.
file(READ ${EXTENSION}/jdpreview.c preview)
string(REPLACE "\"jpegcomp.h\"" "\"jpegapicomp.h\"" preview "${preview}")
file(WRITE ${ROOT}/src/jdpreview.c "${preview}")
configure_file(${EXTENSION}/jdpreview.h ${ROOT}/src/jdpreview.h COPYONLY)
configure_file(${EXTENSION}/jdpreview_entropy.inc ${ROOT}/src/jdpreview_entropy.inc COPYONLY)
file(READ ${ROOT}/src/jdhuff.c entropy)
if(NOT entropy MATCHES "jdpreview_entropy.inc")
    file(APPEND ${ROOT}/src/jdhuff.c "\n#include <stdint.h>\n#include \"jdpreview_entropy.inc\"\n")
endif()
file(READ ${ROOT}/src/jdcoefct.c coefficients)
if(NOT coefficients MATCHES "Pixel scaled-component clearing")
    set(mask_location "  /* Loop to process as much as one whole iMCU row */")
    string(FIND "${coefficients}" "${mask_location}" mask_at)
    set(clear_location "      jzero_far((void *)coef->MCU_buffer[0],\n                (size_t)(cinfo->blocks_in_MCU * sizeof(JBLOCK)));")
    string(FIND "${coefficients}" "${clear_location}" clear_at)
    if(mask_at LESS 0 OR clear_at LESS 0)
        message(FATAL_ERROR "Pinned jpeg coefficient-controller source changed")
    endif()
    set(mask_code [=[  /* Pixel scaled-component clearing: a 1x1 IDCT reads DC only.
   * Keep DC zero initialized for insufficient-data handling. Components with
   * larger IDCTs still need every coefficient zeroed, including chroma. */
  unsigned int pixel_dc_mask = 0;
  int pixel_b;
  for (pixel_b = 0; pixel_b < cinfo->blocks_in_MCU; pixel_b++)
    if (cinfo->cur_comp_info[cinfo->MCU_membership[pixel_b]]->_DCT_scaled_size == 1)
      pixel_dc_mask |= 1u << pixel_b;

]=])
    string(REPLACE "${mask_location}" "${mask_code}${mask_location}" coefficients "${coefficients}")
    set(clear_code [=[      if (pixel_dc_mask == 0) {
        jzero_far((void *)coef->MCU_buffer[0],
                  (size_t)(cinfo->blocks_in_MCU * sizeof(JBLOCK)));
      } else if (pixel_dc_mask == (1u << cinfo->blocks_in_MCU) - 1) {
        for (pixel_b = 0; pixel_b < cinfo->blocks_in_MCU; pixel_b++)
          (*coef->MCU_buffer[pixel_b])[0] = 0;
      } else {
        for (pixel_b = 0; pixel_b < cinfo->blocks_in_MCU; pixel_b++) {
          if (pixel_dc_mask & (1u << pixel_b))
            (*coef->MCU_buffer[pixel_b])[0] = 0;
          else
            jzero_far((void *)coef->MCU_buffer[pixel_b], sizeof(JBLOCK));
        }
      }]=])
    string(REPLACE "${clear_location}" "${clear_code}" coefficients "${coefficients}")
    file(WRITE ${ROOT}/src/jdcoefct.c "${coefficients}")
endif()
file(READ ${ROOT}/CMakeLists.txt build)
if(NOT build MATCHES "Pixel DC preview extension")
    file(APPEND ${ROOT}/CMakeLists.txt
        "\n# Pixel DC preview extension\ntarget_sources(jpeg-static PRIVATE src/jdpreview.c)\n")
endif()
