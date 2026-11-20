import 'dart:async';

import 'package:flutter/services.dart';

/// Telefondaki bir dosya (uygulamanın geçici klasörüne kopyalanmış hâli).
class FileItem {
  final String path;
  final String name;
  final int size;
  final String mime;
  final int ocrPages; // PDF → Word: yazı tanımayla okunan sayfa sayısı
  final String? rel; // çıktı klasörüne göre yol (ZIP'ten çıkanlarda "arsiv/alt/dosya.pdf")

  const FileItem({required this.path, required this.name, required this.size, required this.mime, this.ocrPages = 0, this.rel});

  /// rel'in klasör kısmı ("arsiv/alt"); yoksa boş.
  String get folder {
    final r = rel;
    if (r == null || !r.contains('/')) return '';
    return r.substring(0, r.lastIndexOf('/'));
  }

  factory FileItem.fromMap(Map m) => FileItem(
        path: m['path'] as String,
        name: m['name'] as String,
        size: (m['size'] as num?)?.toInt() ?? 0,
        mime: (m['mime'] as String?) ?? 'application/octet-stream',
        ocrPages: (m['ocrPages'] as num?)?.toInt() ?? 0,
        rel: m['rel'] as String?,
      );

  Map<String, dynamic> toJson() => {'path': path, 'name': name, 'size': size, 'mime': mime};

  String get ext => name.contains('.') ? name.split('.').last.toLowerCase() : '';
  String get baseName => name.contains('.') ? name.substring(0, name.lastIndexOf('.')) : name;

  bool get isPdf => mime == 'application/pdf' || ext == 'pdf';
  bool get isZip => ext == 'zip' || mime == 'application/zip' || mime == 'application/x-zip-compressed';
  bool get isDocx => ext == 'docx' || mime.contains('wordprocessingml');
  bool get isDoc => ext == 'doc' || mime == 'application/msword';
  bool get isImage => mime.startsWith('image/') || const ['jpg', 'jpeg', 'png', 'webp', 'heic', 'heif', 'bmp', 'gif'].contains(ext);
  bool get isVideo => mime.startsWith('video/') || const ['mp4', 'mov', 'mkv', '3gp', 'webm'].contains(ext);
  bool get isAudio => mime.startsWith('audio/') || const ['mp3', 'm4a', 'aac', 'wav', 'ogg', 'opus', 'flac', 'amr'].contains(ext);
}

/// Android tarafındaki araçlara erişim. Hiçbir işlem internete çıkmaz.
class Native {
  Native._() {
    _ch.setMethodCallHandler((call) async {
      switch (call.method) {
        case 'progress':
          final m = call.arguments as Map;
          _progress.add(MapEntry(m['job'] as String, (m['value'] as num).toDouble()));
        case 'shared':
          _shared.add(_files(call.arguments));
        case 'sharedFailed':
          _sharedFailed.add((call.arguments as num).toInt());
      }
      return null;
    });
  }
  static final Native i = Native._();

  static const _ch = MethodChannel('cevirgen/native');
  final _progress = StreamController<MapEntry<String, double>>.broadcast();
  final _shared = StreamController<List<FileItem>>.broadcast();

  Stream<MapEntry<String, double>> get progress => _progress.stream;
  Stream<List<FileItem>> get shared => _shared.stream;
  final _sharedFailed = StreamController<int>.broadcast();
  /// Paylaşılan dosyalardan kaç tanesinin alınamadığı.
  Stream<int> get sharedFailed => _sharedFailed.stream;

  /// Çalışan işi iptal eder; iş "CANCELLED" hatasıyla biter.
  Future<void> cancel(String job) => _ch.invokeMethod('cancel', {'job': job});

  static List<FileItem> _files(dynamic v) =>
      (v as List? ?? const []).map((e) => FileItem.fromMap(e as Map)).toList();

  Future<List<FileItem>> takeShared() async => _files(await _ch.invokeMethod('takeShared'));

  Future<List<FileItem>> pick(List<String> mimes, {bool multiple = false}) async =>
      _files(await _ch.invokeMethod('pick', {'mimes': mimes, 'multiple': multiple}));

  /// İndirilenler/Çevirgen'e (folder verilirse onun altına) kaydeder.
  Future<Map> save(FileItem f, {String folder = ''}) async =>
      (await _ch.invokeMethod('save', {'path': f.path, 'name': f.name, 'mime': f.mime, 'folder': folder})) as Map;

  Future<void> share(List<String> items, String mime) => _ch.invokeMethod('share', {'items': items, 'mime': mime});
  Future<void> open(String item, String mime) => _ch.invokeMethod('open', {'item': item, 'mime': mime});

  Future<int> pdfPages(String path) async => (await _ch.invokeMethod('pdfPages', {'path': path})) as int;

  /// Genel çağrı: sonuç her zaman çıkan dosyaların listesidir.
  Future<List<FileItem>> run(String method, String job, Map<String, dynamic> args) async =>
      _files(await _ch.invokeMethod(method, {...args, 'job': job}));

  Future<Map> mediaInfo(String path) async => (await _ch.invokeMethod('mediaInfo', {'path': path})) as Map;
}

String humanSize(int bytes) {
  if (bytes < 1024) return '$bytes B';
  if (bytes < 1024 * 1024) return '${(bytes / 1024).toStringAsFixed(0)} KB';
  if (bytes < 1024 * 1024 * 1024) return '${(bytes / 1024 / 1024).toStringAsFixed(1).replaceAll('.', ',')} MB';
  return '${(bytes / 1024 / 1024 / 1024).toStringAsFixed(2).replaceAll('.', ',')} GB';
}
