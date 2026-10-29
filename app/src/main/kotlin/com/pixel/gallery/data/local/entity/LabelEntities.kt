package com.pixel.gallery.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

const val FAVOURITES_LABEL_ID = 1L
const val FAVOURITES_LABEL_KIND = "FAVOURITES"

@Entity(
    tableName = "labels",
    indices = [Index(value = ["normalizedName"], unique = true), Index(value = ["systemKind"], unique = true)]
)
data class LabelEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val normalizedName: String,
    val systemKind: String? = null,
    val sortOrder: Int = 0
) {
    val isSystem: Boolean get() = systemKind != null
}

// Media entries are a replaceable MediaStore cache. Never cascade their deletion
// into user-authored labels (including when media enters the vault).
@Entity(
    tableName = "media_labels",
    primaryKeys = ["labelId", "contentId"],
    foreignKeys = [ForeignKey(
        entity = LabelEntry::class,
        parentColumns = ["id"],
        childColumns = ["labelId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index(value = ["contentId"])]
)
data class MediaLabelEntry(val labelId: Long, val contentId: Long)
