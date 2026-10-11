package com.pixel.gallery.di

import android.content.Context
import androidx.room.Room
import com.pixel.gallery.data.local.GalleryDatabase
import com.pixel.gallery.data.local.LABEL_DATABASE_CALLBACK
import com.pixel.gallery.data.local.MIGRATION_3_4
import com.pixel.gallery.data.local.dao.LabelDao
import com.pixel.gallery.data.local.dao.MediaDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): GalleryDatabase {
        return Room.databaseBuilder(
            context,
            GalleryDatabase::class.java,
            "gallery_db"
        ).addMigrations(MIGRATION_3_4)
            .addCallback(LABEL_DATABASE_CALLBACK)
            .build()
    }

    @Provides
    @Singleton
    fun provideMediaDao(db: GalleryDatabase): MediaDao {
        return db.mediaDao()
    }

    @Provides
    @Singleton
    fun provideLabelDao(db: GalleryDatabase): LabelDao = db.labelDao()
}
