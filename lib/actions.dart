import 'package:flutter/material.dart';

import 'native.dart';
import 'store.dart';
import 'theme.dart';

/// files: her türden dosya (yalnızca ZIP yapılabilir)
enum Kind { pdf, docx, image, video, audio, archive, files }

Kind? kindOf(FileItem f) {
  if (f.isZip) return Kind.archive;
  if (f.isPdf) return Kind.pdf;
  if (f.isDocx || f.isDoc) return Kind.docx;
  if (f.isImage) return Kind.image;
  if (f.isVideo) return Kind.video;
  if (f.isAudio) return Kind.audio;
  return null;
}

/// Seçilen dosyalar içinde en çok bulunan tür.
Kind? kindOfAll(List<FileItem> files) {
  final counts = <Kind, int>{};
  for (final f in files) {
    final k = kindOf(f);
    if (k != null) counts[k] = (counts[k] ?? 0) + 1;
  }
  if (counts.isEmpty) return null;
  return (counts.entries.toList()..sort((a, b) => b.value.compareTo(a.value))).first.key;
}

const kindMimes = {
  Kind.pdf: ['application/pdf'],
  Kind.docx: ['application/vnd.openxmlformats-officedocument.wordprocessingml.document', 'application/msword'],
  Kind.image: ['image/*'],
  Kind.video: ['video/*'],
  Kind.audio: ['audio/*'],
  Kind.archive: ['application/zip', 'application/x-zip-compressed'],
  Kind.files: ['*/*'],
};

String kindLabel(Kind k) => switch (k) {
      Kind.pdf => 'PDF',
      Kind.docx => 'Word belgesi',
      Kind.image => 'Fotoğraf',
      Kind.video => 'Video',
      Kind.audio => 'Ses',
      Kind.archive => 'ZIP arşivi',
      Kind.files => 'Dosya',
    };

Tint kindTint(Kind k) => switch (k) {
      Kind.pdf => Tint.pdf,
      Kind.docx => Tint.word,
      Kind.image => Tint.photo,
      Kind.video || Kind.audio => Tint.video,
      Kind.archive || Kind.files => Tint.zip,
    };

IconData kindIcon(Kind k) => switch (k) {
      Kind.pdf => Icons.picture_as_pdf_outlined,
      Kind.docx => Icons.description_outlined,
      Kind.image => Icons.image_outlined,
      Kind.video => Icons.movie_outlined,
      Kind.audio => Icons.music_note_outlined,
      Kind.archive || Kind.files => Icons.folder_zip_outlined,
    };

/// Tek bir dosyanın simgesi/rengi için tür (tanınmayanlar "files").
Kind kindOrFiles(FileItem f) => kindOf(f) ?? Kind.files;

/// Seçenek: kullanıcıya gösterilen birkaç seçimden biri.
class Choice {
  final String label;
  final String? hint;
  final Object value;
  const Choice(this.label, this.value, [this.hint]);
}

class Opt {
  final String key;
  final String label;
  final List<Choice> choices;
  final int defaultIndex;
  const Opt(this.key, this.label, this.choices, {this.defaultIndex = 0});
}

/// Bir işin çalıştırılabilir tarifi.
class Job {
  final String title; // bitince gösterilen: "PDF küçültüldü"
  final String method;
  final Map<String, dynamic> args;
  final String outMime;
  final List<FileItem> inputs;
  const Job({required this.title, required this.method, required this.args, required this.outMime, required this.inputs});
}

enum Extra { none, pages, trim }

class ActionDef {
  final String id;
  final String title;
  final String subtitle;
  final IconData icon;
  final bool recommended;
  final bool needsMany; // birleştirme gibi: sıralama ekranı açılır
  final Extra extra;
  final List<Opt> Function() options;
  final Job Function(List<FileItem> files, Map<String, Object> v) build;

  const ActionDef({
    required this.id,
    required this.title,
    required this.subtitle,
    required this.icon,
    required this.options,
    required this.build,
    this.recommended = false,
    this.needsMany = false,
    this.extra = Extra.none,
  });
}

List<Opt> _none() => const [];

final _actions = <Kind, List<ActionDef>>{
  Kind.pdf: [
    ActionDef(
      id: 'pdf.compress',
      title: 'Küçült',
      subtitle: 'Dosya boyutunu düşür, e-postayla gönder',
      icon: Icons.compress,
      recommended: true,
      options: () => const [
        Opt('level', 'Ne kadar küçülsün?', [
          Choice('Az', 0, 'Kalite neredeyse aynı'),
          Choice('Orta', 1, 'Çoğu belge için en iyisi'),
          Choice('Çok', 2, 'En küçük dosya, resimler biraz bulanık'),
        ], defaultIndex: 1),
      ],
      build: (f, v) => Job(
        title: 'PDF küçültüldü',
        method: 'pdfCompress',
        args: {'path': f.first.path, 'name': '${f.first.baseName} (küçük).pdf', 'level': v['level']},
        outMime: 'application/pdf',
        inputs: f.take(1).toList(),
      ),
    ),
    ActionDef(
      id: 'pdf.merge',
      title: 'Birleştir',
      subtitle: 'Birden çok PDF\'i tek dosya yap',
      icon: Icons.call_merge,
      needsMany: true,
      options: _none,
      build: (f, v) => Job(
        title: 'PDF\'ler birleştirildi',
        method: 'pdfMerge',
        args: {'paths': f.map((e) => e.path).toList(), 'name': 'Birleştirilmiş.pdf'},
        outMime: 'application/pdf',
        inputs: f,
      ),
    ),
    ActionDef(
      id: 'pdf.docx',
      title: 'Word\'e dönüştür',
      subtitle: 'Yazıları düzenlenebilir .docx yap (taranmış sayfalar dahil)',
      icon: Icons.description_outlined,
      options: _none,
      build: (f, v) => Job(
        title: 'PDF, Word belgesi oldu',
        method: 'pdfToDocx',
        args: {'path': f.first.path, 'name': '${f.first.baseName}.docx'},
        outMime: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
        inputs: f.take(1).toList(),
      ),
    ),
    ActionDef(
      id: 'pdf.pages',
      title: 'Sayfa seç / sil',
      subtitle: 'Sadece istediğin sayfaları al',
      icon: Icons.content_cut,
      extra: Extra.pages,
      options: _none,
      build: (f, v) => Job(
        title: 'Sayfalar ayrıldı',
        method: 'pdfExtract',
        args: {'path': f.first.path, 'pages': v['pages'], 'name': '${f.first.baseName} (seçili sayfalar).pdf'},
        outMime: 'application/pdf',
        inputs: f.take(1).toList(),
      ),
    ),
    ActionDef(
      id: 'pdf.split',
      title: 'Sayfalara böl',
      subtitle: 'Her sayfa ayrı bir PDF olsun',
      icon: Icons.view_agenda_outlined,
      options: _none,
      build: (f, v) => Job(
        title: 'PDF sayfalara bölündü',
        method: 'pdfSplitEach',
        args: {'path': f.first.path, 'base': f.first.baseName},
        outMime: 'application/pdf',
        inputs: f.take(1).toList(),
      ),
    ),
  ],
  Kind.docx: [
    ActionDef(
      id: 'docx.pdf',
      title: 'PDF\'e dönüştür',
      subtitle: 'Word belgesini PDF olarak kaydet',
      icon: Icons.picture_as_pdf_outlined,
      recommended: true,
      options: _none,
      build: (f, v) => Job(
        title: 'Word belgesi PDF oldu',
        method: 'docxToPdf',
        args: {'path': f.first.path, 'name': '${f.first.baseName}.pdf'},
        outMime: 'application/pdf',
        inputs: f.take(1).toList(),
      ),
    ),
  ],
  Kind.image: [
    ActionDef(
      id: 'image.convert',
      title: 'Küçült / biçim değiştir',
      subtitle: 'JPG, PNG veya WEBP; daha küçük boyut',
      icon: Icons.photo_size_select_large,
      recommended: true,
      options: () => [
        const Opt('format', 'Biçim', [
          Choice('JPG', 'jpg', 'Her yerde açılır'),
          Choice('PNG', 'png', 'Kayıpsız, büyük dosya'),
          Choice('WEBP', 'webp', 'En küçük dosya'),
        ]),
        const Opt('maxSide', 'Boyut', [
          Choice('Orijinal', 0),
          Choice('Büyük', 2560, 'Uzun kenar 2560 piksel'),
          Choice('Orta', 1600, 'Uzun kenar 1600 piksel'),
          Choice('Küçük', 1080, 'Uzun kenar 1080 piksel'),
        ], defaultIndex: 2),
      ],
      build: (f, v) => Job(
        title: f.length > 1 ? '${f.length} fotoğraf dönüştürüldü' : 'Fotoğraf dönüştürüldü',
        method: 'imageConvert',
        args: {
          'paths': f.map((e) => e.path).toList(),
          'format': v['format'],
          'maxSide': v['maxSide'],
          'quality': Store.i.imageQuality,
        },
        outMime: v['format'] == 'png' ? 'image/png' : v['format'] == 'webp' ? 'image/webp' : 'image/jpeg',
        inputs: f,
      ),
    ),
    ActionDef(
      id: 'image.pdf',
      title: 'PDF yap',
      subtitle: 'Fotoğrafları tek bir PDF\'te topla',
      icon: Icons.picture_as_pdf_outlined,
      needsMany: true,
      options: () => const [
        Opt('pageSize', 'Sayfa', [
          Choice('A4', 'a4', 'Yazdırmaya uygun'),
          Choice('Fotoğraf boyutunda', 'fit', 'Kenar boşluğu yok'),
        ]),
      ],
      build: (f, v) => Job(
        title: 'Fotoğraflar PDF oldu',
        method: 'imagesToPdf',
        args: {'paths': f.map((e) => e.path).toList(), 'pageSize': v['pageSize'], 'name': 'Fotoğraflar.pdf'},
        outMime: 'application/pdf',
        inputs: f,
      ),
    ),
  ],
  Kind.video: [
    ActionDef(
      id: 'video.shrink',
      title: 'Küçült',
      subtitle: 'WhatsApp\'ta paylaşmak için daha küçük video',
      icon: Icons.compress,
      recommended: true,
      options: () => const [
        Opt('height', 'Kalite', [
          Choice('1080p', 1080, 'Yüksek, büyük dosya'),
          Choice('720p', 720, 'Çoğu durum için en iyisi'),
          Choice('480p', 480, 'En küçük dosya'),
        ], defaultIndex: 1),
      ],
      build: (f, v) => Job(
        title: 'Video küçültüldü',
        method: 'mediaTransform',
        args: {'path': f.first.path, 'height': v['height'], 'name': '${f.first.baseName} (${v['height']}p).mp4'},
        outMime: 'video/mp4',
        inputs: f.take(1).toList(),
      ),
    ),
    ActionDef(
      id: 'video.trim',
      title: 'Kırp',
      subtitle: 'Videonun sadece bir bölümünü al',
      icon: Icons.content_cut,
      extra: Extra.trim,
      options: _none,
      build: (f, v) => Job(
        title: 'Video kırpıldı',
        method: 'mediaTransform',
        args: {
          'path': f.first.path,
          'startMs': v['startMs'],
          'endMs': v['endMs'],
          'name': '${f.first.baseName} (kırpılmış).mp4',
        },
        outMime: 'video/mp4',
        inputs: f.take(1).toList(),
      ),
    ),
    _mp3('video.mp3', 'Sesi çıkar (MP3)', 'Videodaki sesi müzik dosyası yap'),
    _m4a('video.m4a', 'Sesi çıkar (M4A)', 'Daha hızlı, küçük ses dosyası'),
  ],
  Kind.audio: [
    _mp3('audio.mp3', 'MP3\'e dönüştür', 'Her cihazda çalan ses dosyası', recommended: true),
    _m4a('audio.m4a', 'M4A\'ya dönüştür', 'Küçük ve kaliteli (AAC)'),
  ],
};

ActionDef _mp3(String id, String title, String sub, {bool recommended = false}) => ActionDef(
      id: id,
      title: title,
      subtitle: sub,
      icon: Icons.music_note_outlined,
      recommended: recommended,
      options: () => const [
        Opt('kbps', 'Kalite', [
          Choice('128 kbps', 128, 'Konuşma, küçük dosya'),
          Choice('192 kbps', 192, 'Müzik için iyi'),
          Choice('320 kbps', 320, 'En yüksek kalite'),
        ], defaultIndex: 1),
      ],
      build: (f, v) => Job(
        title: 'MP3 hazır',
        method: 'toMp3',
        args: {'path': f.first.path, 'kbps': v['kbps'], 'name': '${f.first.baseName}.mp3'},
        outMime: 'audio/mpeg',
        inputs: f.take(1).toList(),
      ),
    );

ActionDef _m4a(String id, String title, String sub) => ActionDef(
      id: id,
      title: title,
      subtitle: sub,
      icon: Icons.graphic_eq,
      options: _none,
      build: (f, v) => Job(
        title: 'M4A hazır',
        method: 'mediaTransform',
        args: {'path': f.first.path, 'audioOnly': true, 'name': '${f.first.baseName}.m4a'},
        outMime: 'audio/mp4',
        inputs: f.take(1).toList(),
      ),
    );

/// Her türden dosyayı tek ZIP'te toplar.
final zipAction = ActionDef(
  id: 'zip',
  title: 'ZIP yap',
  subtitle: 'Dosyaları tek bir sıkıştırılmış dosyada topla',
  icon: Icons.folder_zip_outlined,
  needsMany: true,
  options: _none,
  build: (f, v) => Job(
    title: 'ZIP hazır',
    method: 'zip',
    args: {
      'paths': f.map((e) => e.path).toList(),
      'name': f.length == 1 ? '${f.first.baseName}.zip' : 'Dosyalar.zip',
    },
    outMime: 'application/zip',
    inputs: f,
  ),
);

/// ZIP'lerin içindekileri çıkarır (her arşiv kendi klasörüne).
final unzipAction = ActionDef(
  id: 'unzip',
  title: 'ZIP\'i aç',
  subtitle: 'İçindeki dosyaları çıkar ve kaydet',
  icon: Icons.unarchive_outlined,
  recommended: true,
  options: _none,
  build: (f, v) => Job(
    title: 'ZIP açıldı',
    method: 'unzip',
    args: {'paths': f.map((e) => e.path).toList()},
    outMime: '*/*', // çıkan her dosyanın kendi türü var
    inputs: f,
  ),
);

List<ActionDef> actionsFor(Kind k) => switch (k) {
      Kind.files => [zipAction],
      Kind.archive => [unzipAction],
      _ => [..._actions[k]!, zipAction],
    };

/// "1-3, 5, 8-6" → 0 tabanlı sayfa listesi. Hatalıysa null.
List<int>? parsePages(String text, int pageCount) {
  final out = <int>[];
  for (final raw in text.split(RegExp(r'[,;\s]+'))) {
    final part = raw.trim();
    if (part.isEmpty) continue;
    final m = RegExp(r'^(\d+)(?:-(\d+))?$').firstMatch(part);
    if (m == null) return null;
    final a = int.parse(m.group(1)!);
    final b = m.group(2) == null ? a : int.parse(m.group(2)!);
    if (a < 1 || b < 1 || a > pageCount || b > pageCount) return null;
    if (a <= b) {
      for (var p = a; p <= b; p++) {
        out.add(p - 1);
      }
    } else {
      for (var p = a; p >= b; p--) {
        out.add(p - 1);
      }
    }
  }
  return out.isEmpty ? null : out;
}
