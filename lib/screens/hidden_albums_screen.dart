import 'package:flutter/material.dart';
import 'dart:async';
import '../services/media_service.dart';
import '../services/settings_service.dart';
import '../models/album_model.dart';
import '../widgets/aves_entry_image.dart';
import 'photo_screen.dart';

class HiddenAlbumsScreen extends StatefulWidget {
  const HiddenAlbumsScreen({super.key});

  @override
  State<HiddenAlbumsScreen> createState() => _HiddenAlbumsScreenState();
}

class _HiddenAlbumsScreenState extends State<HiddenAlbumsScreen> {
  List<AlbumModel> _hiddenAlbums = [];
  bool _loading = true;
  final MediaService _service = MediaService();

  @override
  void initState() {
    super.initState();
    _loadHiddenAlbums();
  }

  Future<void> _loadHiddenAlbums() async {
    final hiddenIds = SettingsService().hiddenAlbums;
    if (hiddenIds.isEmpty) {
      setState(() {
        _hiddenAlbums = [];
        _loading = false;
      });
      return;
    }

    final allAlbums = await _service.getPhotos();
    final hidden = allAlbums
        .where((a) => !a.isAll && hiddenIds.contains(a.id))
        .toList();

    if (mounted) {
      setState(() {
        _hiddenAlbums = hidden;
        _loading = false;
      });
    }
  }

  Future<void> _unhideAlbum(AlbumModel album) async {
    final hiddenAlbums = SettingsService().hiddenAlbums;
    hiddenAlbums.remove(album.id);
    await SettingsService().setHiddenAlbums(hiddenAlbums);
    MediaService().rebuildAlbums();

    setState(() {
      _hiddenAlbums.removeWhere((a) => a.id == album.id);
    });

    if (mounted) {
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('"${album.name}" is now visible in Recents')),
      );
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Hidden Albums')),
      body: _loading
          ? const Center(child: CircularProgressIndicator())
          : _hiddenAlbums.isEmpty
          ? Center(
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Icon(
                    Icons.visibility,
                    size: 64,
                    color: Theme.of(
                      context,
                    ).colorScheme.onSurface.withOpacity(0.3),
                  ),
                  const SizedBox(height: 16),
                  Text(
                    'No hidden albums',
                    style: TextStyle(
                      fontSize: 16,
                      color: Theme.of(
                        context,
                      ).colorScheme.onSurface.withOpacity(0.5),
                    ),
                  ),
                  const SizedBox(height: 8),
                  Text(
                    'Long-press an album to hide it from Recents',
                    style: TextStyle(
                      fontSize: 13,
                      color: Theme.of(
                        context,
                      ).colorScheme.onSurface.withOpacity(0.4),
                    ),
                  ),
                ],
              ),
            )
          : ListView.builder(
              itemCount: _hiddenAlbums.length,
              itemBuilder: (context, index) {
                final album = _hiddenAlbums[index];
                return ListTile(
                  leading: ClipRRect(
                    borderRadius: BorderRadius.circular(8),
                    child: SizedBox(
                      width: 56,
                      height: 56,
                      child: album.entries.isNotEmpty
                          ? AvesEntryImage(
                              entry: album.entries.first,
                              extent: 100,
                              fit: BoxFit.cover,
                            )
                          : Container(
                              color: Theme.of(
                                context,
                              ).colorScheme.surfaceVariant,
                              child: const Icon(Icons.photo_library_outlined),
                            ),
                    ),
                  ),
                  title: Text(album.name),
                  subtitle: Text('${album.assetCount} items'),
                  trailing: TextButton.icon(
                    onPressed: () => _unhideAlbum(album),
                    icon: const Icon(Icons.visibility),
                    label: const Text('Unhide'),
                  ),
                  onTap: () {
                    Navigator.push(
                      context,
                      MaterialPageRoute(
                        builder: (_) => PhotoScreen(album: album),
                      ),
                    );
                  },
                );
              },
            ),
    );
  }
}
