import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';

import 'actions.dart';
import 'flow.dart';
import 'native.dart';
import 'other.dart';
import 'store.dart';
import 'theme.dart';

final navKey = GlobalKey<NavigatorState>();

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  await Store.i.load();
  LicenseRegistry.addLicense(extraLicenses);
  runApp(const CevirgenApp());
}

class CevirgenApp extends StatelessWidget {
  const CevirgenApp({super.key});

  @override
  Widget build(BuildContext context) => MaterialApp(
        title: 'Çevirgen',
        debugShowCheckedModeBanner: false,
        navigatorKey: navKey,
        theme: buildTheme(),
        locale: const Locale('tr'),
        supportedLocales: const [Locale('tr')],
        localizationsDelegates: GlobalMaterialLocalizations.delegates,
        home: const Shell(),
      );
}

class Shell extends StatefulWidget {
  const Shell({super.key});
  @override
  State<Shell> createState() => _ShellState();
}

class _ShellState extends State<Shell> {
  int tab = 0;
  StreamSubscription? _sub;
  StreamSubscription? _failSub;

  @override
  void initState() {
    super.initState();
    // "Paylaş → Çevirgen" ile gelen dosyalar
    _sub = Native.i.shared.listen(_openShared);
    _failSub = Native.i.sharedFailed.listen((n) {
      final ctx = navKey.currentContext;
      if (ctx != null && ctx.mounted) toast(ctx, n == 1 ? 'Paylaşılan dosya açılamadı.' : '$n dosya açılamadı.');
    });
    WidgetsBinding.instance.addPostFrameCallback((_) async {
      final first = await Native.i.takeShared();
      if (first.isNotEmpty) _openShared(first);
    });
  }

  void _openShared(List<FileItem> files) {
    final ctx = navKey.currentContext;
    if (ctx == null || files.isEmpty) return;
    navKey.currentState!.popUntil((r) => r.isFirst);
    setState(() => tab = 0);
    startFlow(ctx, files);
  }

  @override
  void dispose() {
    _sub?.cancel();
    _failSub?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => Scaffold(
        body: SafeArea(
          child: IndexedStack(index: tab, children: [
            HomeScreen(onShowHistory: () => setState(() => tab = 1)),
            const HistoryScreen(),
            const SettingsScreen(),
          ]),
        ),
        bottomNavigationBar: NavigationBar(
          selectedIndex: tab,
          onDestinationSelected: (i) => setState(() => tab = i),
          destinations: const [
            NavigationDestination(icon: Icon(Icons.home_outlined), selectedIcon: Icon(Icons.home), label: 'Ana sayfa'),
            NavigationDestination(icon: Icon(Icons.history), label: 'Geçmiş'),
            NavigationDestination(icon: Icon(Icons.settings_outlined), selectedIcon: Icon(Icons.settings), label: 'Ayarlar'),
          ],
        ),
      );
}

class HomeScreen extends StatelessWidget {
  final VoidCallback onShowHistory;
  const HomeScreen({super.key, required this.onShowHistory});

  Future<void> _pick(BuildContext context, List<String> mimes, {bool multiple = true}) async {
    try {
      final files = await Native.i.pick(mimes, multiple: multiple);
      if (files.isNotEmpty && context.mounted) startFlow(context, files);
    } catch (e) {
      if (context.mounted) toast(context, 'Dosya açılamadı: $e');
    }
  }

  @override
  Widget build(BuildContext context) {
    final tiles = [
      _Tile('PDF', 'Küçült, birleştir, Word', Icons.picture_as_pdf_outlined, Tint.pdf, kindMimes[Kind.pdf]!),
      _Tile('Word → PDF', '.docx belgelerini çevir', Icons.description_outlined, Tint.word, kindMimes[Kind.docx]!, multiple: false),
      _Tile('Fotoğraf', 'Küçült, JPG, PDF yap', Icons.image_outlined, Tint.photo, kindMimes[Kind.image]!),
      _Tile('Video ve ses', 'Küçült, kırp, MP3', Icons.movie_outlined, Tint.video, ['video/*', 'audio/*'], multiple: false),
    ];
    return ListView(
      padding: const EdgeInsets.fromLTRB(20, 20, 20, 24),
      children: [
        const Text('Çevirgen', style: TextStyle(fontSize: 30, fontWeight: FontWeight.w800, letterSpacing: -.5)),
        const SizedBox(height: 4),
        const Text('Ne dönüştürmek istersin?', style: TextStyle(fontSize: 17, color: C.text2)),
        const SizedBox(height: 20),
        GridView.count(
          crossAxisCount: 2,
          shrinkWrap: true,
          physics: const NeverScrollableScrollPhysics(),
          mainAxisSpacing: 12,
          crossAxisSpacing: 12,
          childAspectRatio: .98,
          children: [
            for (final t in tiles)
              Box(
                onTap: () => _pick(context, t.mimes, multiple: t.multiple),
                child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                  IconTile(t.icon, t.tint, size: 52),
                  const Spacer(),
                  Text(t.title, style: const TextStyle(fontSize: 18, fontWeight: FontWeight.w700)),
                  const SizedBox(height: 2),
                  Text(t.sub, style: const TextStyle(fontSize: 14, color: C.text2)),
                ]),
              ),
          ],
        ),
        const SizedBox(height: 12),
        // Her türden dosyayı tek ZIP'te toplama
        Box(
          onTap: () async {
            try {
              final files = await Native.i.pick(const ['*/*'], multiple: true);
              if (files.isEmpty || !context.mounted) return;
              // Seçilenlerin hepsi ZIP ise: aç. Değilse: ZIP yap.
              Navigator.of(context).push(MaterialPageRoute(
                  builder: (_) => files.every((f) => f.isZip)
                      ? ChooseScreen(files: files, kind: Kind.archive)
                      : OrderScreen(files: files, kind: Kind.files, action: zipAction)));
            } catch (e) {
              if (context.mounted) toast(context, 'Dosya açılamadı: $e');
            }
          },
          padding: const EdgeInsets.all(14),
          child: const Row(children: [
            IconTile(Icons.folder_zip_outlined, Tint.zip, size: 52),
            SizedBox(width: 14),
            Expanded(
              child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                Text('ZIP', style: TextStyle(fontSize: 18, fontWeight: FontWeight.w700)),
                SizedBox(height: 2),
                Text('Dosyaları tek ZIP\'te topla ya da ZIP\'i aç', style: TextStyle(fontSize: 14, color: C.text2)),
              ]),
            ),
            Icon(Icons.chevron_right, color: C.text2),
          ]),
        ),
        const SizedBox(height: 16),
        FilledButton.icon(
          onPressed: () => _pick(context, const ['*/*']),
          icon: const Icon(Icons.folder_open),
          label: const Text('Dosya seç'),
        ),
        const SizedBox(height: 12),
        Box(
          color: C.accentTint,
          borderColor: C.accentTint,
          padding: const EdgeInsets.all(14),
          child: const Row(children: [
            Icon(Icons.lock_outline, color: C.accent),
            SizedBox(width: 12),
            Expanded(
              child: Text('Dosyaların telefonunda dönüştürülür, internete gönderilmez.',
                  style: TextStyle(fontSize: 15, color: C.accent, fontWeight: FontWeight.w600)),
            ),
          ]),
        ),
        const SizedBox(height: 12),
        const Text('İpucu: Başka bir uygulamada “Paylaş → Çevirgen” diyerek de dosya gönderebilirsin.',
            style: TextStyle(fontSize: 14, color: C.text2)),
        ListenableBuilder(
          listenable: Store.i,
          builder: (context, _) {
            final recent = Store.i.history.take(3).toList();
            if (recent.isEmpty) return const SizedBox.shrink();
            return Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
              const SizedBox(height: 24),
              Row(children: [
                const Expanded(child: Text('Son yapılanlar', style: TextStyle(fontSize: 18, fontWeight: FontWeight.w700))),
                TextButton(onPressed: onShowHistory, child: const Text('Tümü', style: TextStyle(fontSize: 15))),
              ]),
              for (final e in recent) Padding(padding: const EdgeInsets.only(bottom: 8), child: HistoryTile(e)),
            ]);
          },
        ),
      ],
    );
  }
}

class _Tile {
  final String title, sub;
  final IconData icon;
  final Tint tint;
  final List<String> mimes;
  final bool multiple;
  _Tile(this.title, this.sub, this.icon, this.tint, this.mimes, {this.multiple = true});
}
