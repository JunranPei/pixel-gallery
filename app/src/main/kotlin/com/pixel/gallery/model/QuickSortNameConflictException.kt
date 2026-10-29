package com.pixel.gallery.model

class QuickSortNameConflictException(val targetPath: String) : java.io.IOException("Filename already exists: $targetPath")
