import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';

import 'native.dart';
import 'store.dart';
import 'theme.dart';

const _months = ['Oca', 'Şub', 'Mar', 'Nis', 'May', 'Haz', 'Tem', 'Ağu', 'Eyl', 'Eki', 'Kas', 'Ara'];

String when(DateTime t) {
  final now = DateTime.now();
  String two(int n) => n.toString().padLeft(2, '0');
  final hm = '${two(t.hour)}:${two(t.minute)}';
  final today = DateTime(now.year, now.month, now.day);
  final day = DateTime(t.year, t.month, t.day);
  final diff = today.difference(day).inDays;
  if (diff == 0) return 'Bugün $hm';
  if (diff == 1) return 'Dün $hm';
  return '${t.day} ${_months[t.month - 1]}${t.year != now.year ? ' ${t.year}' : ''} $hm';
}

IconData _icon(String mime) => mime == 'application/pdf'
    ? Icons.picture_as_pdf_outlined
    : mime == 'application/zip' || mime == '*/*'
        ? Icons.folder_zip_outlined
        : mime.contains('wordprocessingml')
        ? Icons.description_outlined
        : mime.startsWith('image/')
            ? Icons.image_outlined
            : mime.startsWith('video/')
                ? Icons.movie_outlined
                : Icons.music_note_outlined;

Tint _tint(String mime) => mime == 'application/pdf'
    ? Tint.pdf
    : mime == 'application/zip' || mime == '*/*'
        ? Tint.zip
        : mime.contains('wordprocessingml')
        ? Tint.word
        : mime.startsWith('image/')
        ? Tint.photo
        : Tint.video;

class HistoryTile extends StatelessWidget {
  final HistoryEntry e;
  const HistoryTile(this.e, {super.key});

  Future<void> _open(BuildContext context) async {
    if (e.savedUri == null) {
      toast(context, e.count > 1 ? 'Dosyalar İndirilenler/Çevirgen klasöründe.' : 'Bu dosya kaydedilmemişti.');
      return;
    }
    try {
      await Native.i.open(e.savedUri!, e.mime);
    } catch (_) {
      if (context.mounted) toast(context, 'Dosya bulunamadı. Silinmiş ya da taşınmış olabilir.');
    }
  }

  @override
  Widget build(BuildContext context) {
    final smaller = e.inSize > 0 && e.outSize < e.inSize && e.count == 1;
    return Box(
      padding: const EdgeInsets.fromLTRB(14, 10, 4, 10),
      onTap: () => _open(context),
      child: Row(children: [
        IconTile(_icon(e.mime), _tint(e.mime), size: 42),
        const SizedBox(width: 12),
        Expanded(
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Text(e.outName, maxLines: 1, overflow: TextOverflow.ellipsis, style: const TextStyle(fontSize: 16, fontWeight: FontWeight.w600)),
            const SizedBox(height: 2),
            Text(
              '${e.title} · ${smaller ? '${humanSize(e.inSize)} → ${humanSize(e.outSize)}' : humanSize(e.outSize)}',
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: const TextStyle(fontSize: 14, color: C.text2),
            ),
            Text(when(e.at), style: const TextStyle(fontSize: 13, color: C.text2)),
          ]),
        ),
        PopupMenuButton<String>(
          tooltip: 'Seçenekler',
          onSelected: (v) async {
            if (v == 'share' && e.savedUri != null) {
              await Native.i.share([e.savedUri!], e.mime);
            } else if (v == 'remove') {
              await Store.i.remove(e);
            }
          },
          itemBuilder: (_) => [
            if (e.savedUri != null) const PopupMenuItem(value: 'share', child: Text('Paylaş')),
            const PopupMenuItem(value: 'remove', child: Text('Listeden kaldır')),
          ],
        ),
      ]),
    );
  }
}

class HistoryScreen extends StatelessWidget {
  const HistoryScreen({super.key});

  @override
  Widget build(BuildContext context) => ListenableBuilder(
        listenable: Store.i,
        builder: (context, _) {
          final h = Store.i.history;
          return ListView(
            padding: const EdgeInsets.fromLTRB(20, 20, 20, 24),
            children: [
              Row(children: [
                const Expanded(child: Text('Geçmiş', style: TextStyle(fontSize: 28, fontWeight: FontWeight.w800))),
                if (h.isNotEmpty)
                  TextButton(
                    onPressed: () async {
                      final ok = await showDialog<bool>(
                        context: context,
                        builder: (c) => AlertDialog(
                          title: const Text('Geçmiş temizlensin mi?'),
                          content: const Text('Sadece liste silinir. Kaydettiğin dosyalar İndirilenler\'de kalır.',
                              style: TextStyle(fontSize: 16)),
                          actions: [
                            TextButton(onPressed: () => Navigator.pop(c, false), child: const Text('Vazgeç')),
                            FilledButton(
                              style: FilledButton.styleFrom(minimumSize: const Size(100, 44)),
                              onPressed: () => Navigator.pop(c, true),
                              child: const Text('Temizle'),
                            ),
                          ],
                        ),
                      );
                      if (ok == true) await Store.i.clearHistory();
                    },
                    child: const Text('Temizle', style: TextStyle(fontSize: 15)),
                  ),
              ]),
              const SizedBox(height: 4),
              const Text('Kaydedilen dosyalar: İndirilenler/Çevirgen', style: TextStyle(fontSize: 15, color: C.text2)),
              const SizedBox(height: 16),
              if (h.isEmpty)
                const Padding(
                  padding: EdgeInsets.only(top: 60),
                  child: Column(children: [
                    Icon(Icons.history, size: 56, color: C.border),
                    SizedBox(height: 12),
                    Text('Henüz bir şey dönüştürmedin.', style: TextStyle(fontSize: 17, color: C.text2)),
                  ]),
                ),
              for (final e in h) Padding(padding: const EdgeInsets.only(bottom: 8), child: HistoryTile(e)),
            ],
          );
        },
      );
}

class SettingsScreen extends StatelessWidget {
  const SettingsScreen({super.key});

  @override
  Widget build(BuildContext context) => ListenableBuilder(
        listenable: Store.i,
        builder: (context, _) {
          final s = Store.i;
          return ListView(
            padding: const EdgeInsets.fromLTRB(20, 20, 20, 24),
            children: [
              const Text('Ayarlar', style: TextStyle(fontSize: 28, fontWeight: FontWeight.w800)),
              const SizedBox(height: 16),
              Box(
                padding: EdgeInsets.zero,
                child: Column(children: [
                  SwitchListTile(
                    value: s.autoSave,
                    onChanged: s.setAutoSave,
                    title: const Text('Bitince otomatik kaydet', style: TextStyle(fontSize: 17, fontWeight: FontWeight.w600)),
                    subtitle: const Text('İndirilenler/Çevirgen klasörüne', style: TextStyle(fontSize: 14)),
                  ),
                  const Divider(height: 1, color: C.border),
                  SwitchListTile(
                    value: s.keepHistory,
                    onChanged: s.setKeepHistory,
                    title: const Text('Geçmişi tut', style: TextStyle(fontSize: 17, fontWeight: FontWeight.w600)),
                    subtitle: const Text('Kapatınca liste de silinir', style: TextStyle(fontSize: 14)),
                  ),
                ]),
              ),
              const SizedBox(height: 20),
              const Text('Fotoğraf kalitesi (JPG/WEBP)', style: TextStyle(fontSize: 18, fontWeight: FontWeight.w700)),
              const SizedBox(height: 8),
              SegmentedButton<int>(
                segments: const [
                  ButtonSegment(value: 70, label: Text('Küçük dosya')),
                  ButtonSegment(value: 85, label: Text('Dengeli')),
                  ButtonSegment(value: 95, label: Text('En iyi')),
                ],
                selected: {s.imageQuality},
                onSelectionChanged: (v) => s.setImageQuality(v.first),
                showSelectedIcon: false,
              ),
              const SizedBox(height: 24),
              Box(
                color: C.accentTint,
                borderColor: C.accentTint,
                child: const Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                  Row(children: [
                    Icon(Icons.lock_outline, color: C.accent),
                    SizedBox(width: 8),
                    Text('Gizlilik', style: TextStyle(fontSize: 18, fontWeight: FontWeight.w700, color: C.accent)),
                  ]),
                  SizedBox(height: 8),
                  Text(
                    'Çevirgen internet izni istemez; dosyaların hiçbir sunucuya gönderilmez. '
                    'Dönüştürme sırasında oluşan geçici kopyalar 24 saat sonra kendiliğinden silinir.',
                    style: TextStyle(fontSize: 15, height: 1.35),
                  ),
                ]),
              ),
              const SizedBox(height: 12),
              Box(
                padding: EdgeInsets.zero,
                child: ListTile(
                  leading: const Icon(Icons.article_outlined),
                  title: const Text('Açık kaynak lisansları', style: TextStyle(fontSize: 17, fontWeight: FontWeight.w600)),
                  subtitle: const Text('PDFBox, Media3, docx-preview, JSZip, LAME', style: TextStyle(fontSize: 14)),
                  trailing: const Icon(Icons.chevron_right),
                  onTap: () => showLicensePage(
                    context: context,
                    applicationName: 'Çevirgen',
                    applicationVersion: '1.0.0',
                    applicationLegalese: 'Nashi (MIT, Orkun Işıtmak) uygulamasından esinlenmiştir.',
                  ),
                ),
              ),
              const SizedBox(height: 16),
              const Center(child: Text('Çevirgen 1.0.0', style: TextStyle(fontSize: 14, color: C.text2))),
            ],
          );
        },
      );
}

/// Uygulamaya gömülü kütüphanelerin lisans bildirimleri.
Stream<LicenseEntry> extraLicenses() async* {
  yield const LicenseEntryWithLineBreaks(['PdfBox-Android'],
      'PdfBox-Android (Tom Roush), Apache PDFBox tabanlıdır.\nApache License, Version 2.0\nhttps://www.apache.org/licenses/LICENSE-2.0\nhttps://github.com/TomRoush/PdfBox-Android');
  yield const LicenseEntryWithLineBreaks(['AndroidX Media3'],
      'Copyright The Android Open Source Project.\nApache License, Version 2.0\nhttps://www.apache.org/licenses/LICENSE-2.0');
  yield const LicenseEntryWithLineBreaks(['docx-preview'],
      'docx-preview (VolodymyrBaydalka).\nApache License, Version 2.0\nhttps://github.com/VolodymyrBaydalka/docxjs');
  yield const LicenseEntryWithLineBreaks(['JSZip'],
      'JSZip – Stuart Knightley ve katkıcılar.\nMIT veya GPLv3 çift lisans; bu uygulamada MIT koşulları geçerlidir.\nhttps://stuk.github.io/jszip/');
  yield const LicenseEntryWithLineBreaks(['jump3r (LAME)'],
      'jump3r 1.0.5 – LAME MP3 kodlayıcısının Java uyarlaması (Hanns Holger Rutz ve LAME geliştiricileri).\n'
      'GNU Lesser General Public License v2.1 (LGPL)\nhttps://www.gnu.org/licenses/old-licenses/lgpl-2.1.html\n'
      'Kaynak kod: https://github.com/Sciss/jump3r\n'
      'Kütüphane değiştirilmeden kullanılmıştır; istenirse kendi derlemenizle değiştirilebilir.');
  yield const LicenseEntryWithLineBreaks(['Google ML Kit – Metin tanıma'],
      'Taranmış PDF sayfalarındaki yazıları okumak için kullanılır. Model uygulamanın içindedir ve telefonda çalışır; '
      'Çevirgen internet izni istemediği için hiçbir veri gönderilemez.\nhttps://developers.google.com/ml-kit/terms');
  yield const LicenseEntryWithLineBreaks(['Nashi'],
      'Çevirgen, Nashi (Orkun Işıtmak, MIT Lisansı) uygulamasının fikrinden esinlenmiştir.');
}
