import 'dart:convert';

import 'package:flutter/foundation.dart';
import 'package:shared_preferences/shared_preferences.dart';

/// Geçmişteki bir dönüşüm. Kaydedilen dosyanın İndirilenler'deki adresi tutulur.
class HistoryEntry {
  final String title; // "PDF küçültüldü"
  final String outName;
  final int inSize;
  final int outSize;
  final int count; // çıkan dosya sayısı
  final String mime;
  final String? savedUri; // content:// (İndirilenler/Çevirgen)
  final DateTime at;

  HistoryEntry({
    required this.title,
    required this.outName,
    required this.inSize,
    required this.outSize,
    required this.count,
    required this.mime,
    required this.savedUri,
    required this.at,
  });

  Map<String, dynamic> toJson() => {
        't': title, 'n': outName, 'i': inSize, 'o': outSize, 'c': count, 'm': mime, 'u': savedUri,
        'a': at.millisecondsSinceEpoch,
      };

  factory HistoryEntry.fromJson(Map<String, dynamic> j) => HistoryEntry(
        title: j['t'] as String,
        outName: j['n'] as String,
        inSize: (j['i'] as num).toInt(),
        outSize: (j['o'] as num).toInt(),
        count: (j['c'] as num?)?.toInt() ?? 1,
        mime: j['m'] as String,
        savedUri: j['u'] as String?,
        at: DateTime.fromMillisecondsSinceEpoch((j['a'] as num).toInt()),
      );
}

class Store extends ChangeNotifier {
  Store._();
  static final Store i = Store._();
  late SharedPreferences _p;

  List<HistoryEntry> history = [];
  bool autoSave = true; // bitince İndirilenler'e otomatik kaydet
  bool keepHistory = true;
  int imageQuality = 85;

  Future<void> load() async {
    _p = await SharedPreferences.getInstance();
    autoSave = _p.getBool('autoSave') ?? true;
    keepHistory = _p.getBool('keepHistory') ?? true;
    imageQuality = _p.getInt('imageQuality') ?? 85;
    try {
      history = (_p.getStringList('history') ?? [])
          .map((s) => HistoryEntry.fromJson(jsonDecode(s) as Map<String, dynamic>))
          .toList();
    } catch (_) {
      history = [];
    }
  }

  Future<void> add(HistoryEntry e) async {
    if (!keepHistory) return;
    history.insert(0, e);
    if (history.length > 100) history = history.sublist(0, 100);
    await _saveHistory();
  }

  Future<void> remove(HistoryEntry e) async {
    history.remove(e);
    await _saveHistory();
  }

  Future<void> clearHistory() async {
    history.clear();
    await _saveHistory();
  }

  Future<void> _saveHistory() async {
    await _p.setStringList('history', history.map((e) => jsonEncode(e.toJson())).toList());
    notifyListeners();
  }

  Future<void> setAutoSave(bool v) async {
    autoSave = v;
    await _p.setBool('autoSave', v);
    notifyListeners();
  }

  Future<void> setKeepHistory(bool v) async {
    keepHistory = v;
    await _p.setBool('keepHistory', v);
    if (!v) await clearHistory();
    notifyListeners();
  }

  Future<void> setImageQuality(int v) async {
    imageQuality = v;
    await _p.setInt('imageQuality', v);
    notifyListeners();
  }
}
