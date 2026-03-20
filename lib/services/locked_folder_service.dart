import 'package:shared_preferences/shared_preferences.dart';
import '../models/aves_entry.dart';
import 'local_db.dart';

/// Manages the set of content IDs that have been moved into the Locked Folder.
/// Entries in this set are excluded from all normal views (Recent, Albums, etc.)
/// and are only shown inside the Locked Folder screen after authentication.
class LockedFolderService {
  static final LockedFolderService _instance = LockedFolderService._internal();
  factory LockedFolderService() => _instance;
  LockedFolderService._internal();

  static const String _lockedIdsKey = 'locked_folder_ids';

  SharedPreferences? _prefs;
  Set<int> _lockedIds = {};

  /// Must be called once during app start-up (after SettingsService.init).
  Future<void> init() async {
    _prefs ??= await SharedPreferences.getInstance();
    _lockedIds = _loadIds();
  }

  Set<int> _loadIds() {
    final list = _prefs?.getStringList(_lockedIdsKey);
    if (list == null) return {};
    return list.map((s) => int.tryParse(s)).whereType<int>().toSet();
  }

  Future<void> _persist() async {
    await _prefs?.setStringList(
      _lockedIdsKey,
      _lockedIds.map((id) => id.toString()).toList(),
    );
  }

  /// Returns the current set of locked content IDs (O(1) lookup).
  Set<int> get lockedIds => _lockedIds;

  /// Whether a given content ID is locked.
  bool isLocked(int? contentId) {
    if (contentId == null) return false;
    return _lockedIds.contains(contentId);
  }

  /// Move an entry into the Locked Folder.
  Future<void> lock(AvesEntry entry) async {
    if (entry.contentId == null) return;
    _lockedIds.add(entry.contentId!);
    await _persist();
  }

  /// Unlock an entry (remove it from the Locked Folder).
  Future<void> unlock(AvesEntry entry) async {
    if (entry.contentId == null) return;
    _lockedIds.remove(entry.contentId!);
    await _persist();
  }

  /// Unlock multiple entries at once.
  Future<void> unlockAll(List<AvesEntry> entries) async {
    for (final entry in entries) {
      if (entry.contentId != null) {
        _lockedIds.remove(entry.contentId!);
      }
    }
    await _persist();
  }

  /// Lock multiple entries at once.
  Future<void> lockAll(List<AvesEntry> entries) async {
    for (final entry in entries) {
      if (entry.contentId != null) {
        _lockedIds.add(entry.contentId!);
      }
    }
    await _persist();
  }

  /// Return all locked entries from the database.
  Future<List<AvesEntry>> getLockedEntries() async {
    if (_lockedIds.isEmpty) return [];
    final db = LocalDatabase();
    return db.getEntriesByIds(_lockedIds.toList());
  }
}
