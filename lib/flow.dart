import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import 'actions.dart';
import 'native.dart';
import 'store.dart';
import 'theme.dart';

/// Seçilen dosyalarla akışı başlatır.
void startFlow(BuildContext context, List<FileItem> files) {
  final kind = kindOfAll(files);
  final mixed = kind == null || files.any((f) => kindOf(f) != kind);
  if (mixed) {
    // Tanınmayan ya da farklı türde dosyalar: hepsi birlikte ZIP yapılabilir
    Navigator.of(context).push(MaterialPageRoute(builder: (_) => ChooseScreen(files: files, kind: Kind.files)));
    return;
  }
  final same = files;
  if (kind == Kind.docx && same.every((f) => f.isDoc)) {
    showDialog(
      context: context,
      builder: (c) => AlertDialog(
        title: const Text('Eski Word biçimi (.doc)'),
        content: const Text(
            'Çevirgen yeni Word belgelerini (.docx) PDF yapabilir. Bu dosya eski .doc biçiminde.\n\n'
            'Belgeyi Word veya Google Dokümanlar\'da açıp “.docx olarak kaydet” dedikten sonra tekrar deneyebilirsin.',
            style: TextStyle(fontSize: 16)),
        actions: [FilledButton(onPressed: () => Navigator.pop(c), child: const Text('Tamam'))],
      ),
    );
    return;
  }
  Navigator.of(context).push(MaterialPageRoute(builder: (_) => ChooseScreen(files: same, kind: kind)));
}

Widget fileCard(FileItem f, Kind kind, {Widget? trailing}) {
  // Karışık listede (ZIP) her dosya kendi türünün simgesiyle görünür
  final k = kind == Kind.files ? kindOrFiles(f) : kind;
  return Box(
      padding: const EdgeInsets.all(14),
      child: Row(children: [
        IconTile(kindIcon(k), kindTint(k), size: 44),
        const SizedBox(width: 12),
        Expanded(
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Text(f.name, maxLines: 2, overflow: TextOverflow.ellipsis, style: const TextStyle(fontSize: 16, fontWeight: FontWeight.w600)),
            const SizedBox(height: 2),
            Text('${kindLabel(k)} · ${humanSize(f.size)}', style: const TextStyle(fontSize: 14, color: C.text2)),
          ]),
        ),
        ?trailing,
      ]),
    );
}

// ---------------------------------------------------------------- Ne yapalım?

class ChooseScreen extends StatelessWidget {
  final List<FileItem> files;
  final Kind kind;
  const ChooseScreen({super.key, required this.files, required this.kind});

  void _go(BuildContext context, ActionDef a) {
    final nav = Navigator.of(context);
    if (a.needsMany) {
      nav.push(MaterialPageRoute(builder: (_) => OrderScreen(files: files, kind: kind, action: a)));
    } else if (a.options().isNotEmpty || a.extra != Extra.none) {
      nav.push(MaterialPageRoute(builder: (_) => OptionsScreen(files: files, kind: kind, action: a)));
    } else {
      nav.push(MaterialPageRoute(builder: (_) => RunScreen(job: a.build(files, const {}))));
    }
  }

  @override
  Widget build(BuildContext context) {
    final total = files.fold<int>(0, (s, f) => s + f.size);
    return Scaffold(
      appBar: AppBar(title: const Text('Ne yapalım?')),
      body: ListView(
        padding: const EdgeInsets.fromLTRB(20, 4, 20, 24),
        children: [
          if (files.length == 1)
            fileCard(files.first, kind)
          else
            Box(
              padding: const EdgeInsets.all(14),
              child: Row(children: [
                IconTile(kindIcon(kind), kindTint(kind), size: 44),
                const SizedBox(width: 12),
                Expanded(
                  child: Text('${files.length} ${kindLabel(kind).toLowerCase()} · ${humanSize(total)}',
                      style: const TextStyle(fontSize: 16, fontWeight: FontWeight.w600)),
                ),
              ]),
            ),
          const SizedBox(height: 20),
          for (final a in actionsFor(kind))
            Padding(
              padding: const EdgeInsets.only(bottom: 10),
              child: Box(
                onTap: () => _go(context, a),
                borderColor: a.recommended ? C.accent : null,
                child: Row(children: [
                  Container(
                    width: 44,
                    height: 44,
                    decoration: BoxDecoration(color: a.recommended ? C.accentTint : C.bg, borderRadius: BorderRadius.circular(12)),
                    child: Icon(a.icon, color: a.recommended ? C.accent : C.text),
                  ),
                  const SizedBox(width: 14),
                  Expanded(
                    child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                      Row(children: [
                        Flexible(child: Text(a.title, style: const TextStyle(fontSize: 18, fontWeight: FontWeight.w700))),
                        if (a.recommended) ...[
                          const SizedBox(width: 8),
                          Container(
                            padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
                            decoration: BoxDecoration(color: C.accent, borderRadius: BorderRadius.circular(8)),
                            child: const Text('Önerilen', style: TextStyle(color: Colors.white, fontSize: 12, fontWeight: FontWeight.w700)),
                          ),
                        ],
                      ]),
                      const SizedBox(height: 2),
                      Text(a.subtitle, style: const TextStyle(fontSize: 14.5, color: C.text2)),
                    ]),
                  ),
                  const Icon(Icons.chevron_right, color: C.text2),
                ]),
              ),
            ),
        ],
      ),
    );
  }
}

// ---------------------------------------------------------------- Sıralama (birleştir / PDF yap)

class OrderScreen extends StatefulWidget {
  final List<FileItem> files;
  final Kind kind;
  final ActionDef action;
  const OrderScreen({super.key, required this.files, required this.kind, required this.action});
  @override
  State<OrderScreen> createState() => _OrderScreenState();
}

class _OrderScreenState extends State<OrderScreen> {
  late final List<FileItem> files = [...widget.files];

  Future<void> _add() async {
    try {
      // ZIP her türü kabul eder; diğerleri yalnızca kendi türünü
      final isZip = widget.action.id == 'zip';
      final more = await Native.i.pick(isZip ? const ['*/*'] : kindMimes[widget.kind]!, multiple: true);
      setState(() => files.addAll(isZip ? more : more.where((f) => kindOf(f) == widget.kind)));
    } catch (e) {
      if (mounted) toast(context, 'Dosya eklenemedi: $e');
    }
  }

  void _next() {
    final a = widget.action;
    final nav = Navigator.of(context);
    if (a.options().isNotEmpty) {
      nav.push(MaterialPageRoute(builder: (_) => OptionsScreen(files: files, kind: widget.kind, action: a)));
    } else {
      nav.push(MaterialPageRoute(builder: (_) => RunScreen(job: a.build(files, const {}))));
    }
  }

  @override
  Widget build(BuildContext context) {
    final isZip = widget.action.id == 'zip';
    final isPdf = !isZip && widget.kind == Kind.pdf;
    final min = isPdf ? 2 : 1;
    final total = files.fold<int>(0, (s, f) => s + f.size);
    return Scaffold(
      appBar: AppBar(title: Text(isZip ? 'ZIP yap' : isPdf ? 'PDF birleştir' : 'Fotoğraflardan PDF')),
      body: Column(children: [
        Padding(
          padding: const EdgeInsets.fromLTRB(20, 0, 20, 8),
          child: Text(
              isZip
                  ? 'ZIP\'e girecek dosyalar (${humanSize(total)}). Başka dosya ekleyebilir ya da çıkarabilirsin.'
                  : 'Sıralamak için tutup sürükle. ${isPdf ? 'Dosyalar' : 'Fotoğraflar'} bu sırayla eklenecek.',
              style: const TextStyle(fontSize: 15, color: C.text2)),
        ),
        Expanded(
          child: ReorderableListView.builder(
            padding: const EdgeInsets.symmetric(horizontal: 20),
            itemCount: files.length,
            onReorderItem: (a, b) => setState(() => files.insert(b, files.removeAt(a))),
            itemBuilder: (context, i) => Padding(
              key: ObjectKey(files[i]),
              padding: const EdgeInsets.only(bottom: 8),
              child: fileCard(
                files[i],
                widget.kind,
                trailing: Row(mainAxisSize: MainAxisSize.min, children: [
                  Text('${i + 1}', style: const TextStyle(fontSize: 16, fontWeight: FontWeight.w700, color: C.text2)),
                  IconButton(
                    tooltip: 'Çıkar',
                    onPressed: () => setState(() => files.removeAt(i)),
                    icon: const Icon(Icons.close),
                  ),
                  const Icon(Icons.drag_handle, color: C.text2),
                ]),
              ),
            ),
          ),
        ),
        Padding(
          padding: const EdgeInsets.fromLTRB(20, 8, 20, 16),
          child: Column(children: [
            OutlinedButton.icon(onPressed: _add, icon: const Icon(Icons.add), label: const Text('Dosya ekle')),
            const SizedBox(height: 10),
            FilledButton(
              onPressed: files.length >= min ? _next : null,
              child: Text(files.length < min
                  ? 'En az $min dosya seç'
                  : isZip
                      ? (files.length == 1 ? 'ZIP yap' : '${files.length} dosyayı ZIP yap')
                      : isPdf
                          ? '${files.length} dosyayı birleştir'
                          : 'Devam'),
            ),
          ]),
        ),
      ]),
    );
  }
}

// ---------------------------------------------------------------- Seçenekler

class OptionsScreen extends StatefulWidget {
  final List<FileItem> files;
  final Kind kind;
  final ActionDef action;
  const OptionsScreen({super.key, required this.files, required this.kind, required this.action});
  @override
  State<OptionsScreen> createState() => _OptionsScreenState();
}

class _OptionsScreenState extends State<OptionsScreen> {
  late final opts = widget.action.options();
  late final Map<String, int> sel = {for (final o in opts) o.key: o.defaultIndex};
  final pagesCtl = TextEditingController();
  int? pageCount;
  int? durationMs;
  RangeValues? trim;
  String? loadError;

  @override
  void initState() {
    super.initState();
    final f = widget.files.first;
    if (widget.action.extra == Extra.pages) {
      Native.i.pdfPages(f.path).then((n) => setState(() => pageCount = n)).catchError((Object e) {
        setState(() => loadError = _msg(e));
      });
    } else if (widget.action.extra == Extra.trim) {
      Native.i.mediaInfo(f.path).then((m) {
        final d = (m['durationMs'] as num).toInt();
        setState(() {
          durationMs = d;
          trim = RangeValues(0, d / 1000.0);
        });
      }).catchError((Object e) {
        setState(() => loadError = _msg(e));
      });
    }
  }

  @override
  void dispose() {
    pagesCtl.dispose();
    super.dispose();
  }

  List<int>? get pages => pageCount == null ? null : parsePages(pagesCtl.text, pageCount!);

  bool get ready => switch (widget.action.extra) {
        Extra.none => true,
        Extra.pages => pages != null,
        Extra.trim => trim != null && trim!.end - trim!.start >= 1,
      };

  void _start() {
    final v = <String, Object>{for (final o in opts) o.key: o.choices[sel[o.key]!].value};
    if (widget.action.extra == Extra.pages) v['pages'] = pages!;
    if (widget.action.extra == Extra.trim) {
      v['startMs'] = (trim!.start * 1000).round();
      v['endMs'] = (trim!.end * 1000).round();
    }
    Navigator.of(context).push(MaterialPageRoute(builder: (_) => RunScreen(job: widget.action.build(widget.files, v))));
  }

  @override
  Widget build(BuildContext context) {
    // Başlat düğmesi gövdenin altında: klavye açılınca da görünür kalır
    return Scaffold(
      appBar: AppBar(title: Text(widget.action.title)),
      body: Column(children: [
        Expanded(child: ListView(
        padding: const EdgeInsets.fromLTRB(20, 4, 20, 24),
        children: [
          if (widget.files.length == 1) fileCard(widget.files.first, widget.kind),
          if (widget.files.length > 1)
            Text('${widget.files.length} dosya seçildi', style: const TextStyle(fontSize: 16, color: C.text2)),
          for (final o in opts) ...[
            const SizedBox(height: 22),
            Text(o.label, style: const TextStyle(fontSize: 18, fontWeight: FontWeight.w700)),
            const SizedBox(height: 10),
            for (var i = 0; i < o.choices.length; i++)
              Padding(
                padding: const EdgeInsets.only(bottom: 8),
                child: Box(
                  onTap: () => setState(() => sel[o.key] = i),
                  borderColor: sel[o.key] == i ? C.accent : null,
                  padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
                  child: Row(children: [
                    Icon(sel[o.key] == i ? Icons.radio_button_checked : Icons.radio_button_off,
                        color: sel[o.key] == i ? C.accent : C.text2),
                    const SizedBox(width: 12),
                    Expanded(
                      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                        Text(o.choices[i].label, style: const TextStyle(fontSize: 17, fontWeight: FontWeight.w600)),
                        if (o.choices[i].hint != null)
                          Text(o.choices[i].hint!, style: const TextStyle(fontSize: 14, color: C.text2)),
                      ]),
                    ),
                  ]),
                ),
              ),
          ],
          if (loadError != null) ...[
            const SizedBox(height: 20),
            Text(loadError!, style: const TextStyle(fontSize: 16, color: C.danger)),
          ],
          if (widget.action.extra == Extra.pages && loadError == null) ..._pagesUi(),
          if (widget.action.extra == Extra.trim && loadError == null) ..._trimUi(),
        ],
        )),
        SafeArea(
          top: false,
          child: Padding(
            padding: const EdgeInsets.fromLTRB(20, 8, 20, 12),
            child: FilledButton(onPressed: ready ? _start : null, child: const Text('Başlat')),
          ),
        ),
      ]),
    );
  }

  List<Widget> _pagesUi() {
    if (pageCount == null) return const [SizedBox(height: 40), Center(child: CircularProgressIndicator())];
    final p = pages;
    return [
      const SizedBox(height: 22),
      Text('Belgede $pageCount sayfa var', style: const TextStyle(fontSize: 18, fontWeight: FontWeight.w700)),
      const SizedBox(height: 6),
      const Text('Almak istediğin sayfaları yaz. Yazdığın sırayla eklenir; yazmadıkların çıkarılır.',
          style: TextStyle(fontSize: 15, color: C.text2)),
      const SizedBox(height: 12),
      TextField(
        controller: pagesCtl,
        autofocus: true,
        keyboardType: TextInputType.text,
        inputFormatters: [FilteringTextInputFormatter.allow(RegExp(r'[0-9,\-\s]'))],
        style: const TextStyle(fontSize: 20),
        decoration: InputDecoration(
          hintText: 'Örnek: 1-3, 5',
          filled: true,
          fillColor: Colors.white,
          border: OutlineInputBorder(borderRadius: BorderRadius.circular(14)),
        ),
        onChanged: (_) => setState(() {}),
      ),
      const SizedBox(height: 8),
      Text(
        pagesCtl.text.trim().isEmpty
            ? ''
            : p == null
                ? 'Sayfa numaraları 1 ile $pageCount arasında olmalı.'
                : '${p.length} sayfalık yeni bir PDF oluşacak.',
        style: TextStyle(fontSize: 15, color: p == null ? C.danger : C.accent, fontWeight: FontWeight.w600),
      ),
      const SizedBox(height: 8),
      Wrap(spacing: 8, children: [
        ActionChip(label: const Text('İlk sayfa'), onPressed: () => _setPages('1')),
        if (pageCount! > 1) ActionChip(label: const Text('İlk sayfayı sil'), onPressed: () => _setPages('2-$pageCount')),
        if (pageCount! > 1) ActionChip(label: const Text('Son sayfayı sil'), onPressed: () => _setPages('1-${pageCount! - 1}')),
        if (pageCount! > 1) ActionChip(label: const Text('Ters çevir'), onPressed: () => _setPages('$pageCount-1')),
      ]),
    ];
  }

  void _setPages(String s) => setState(() => pagesCtl.text = s);

  List<Widget> _trimUi() {
    if (durationMs == null || trim == null) return const [SizedBox(height: 40), Center(child: CircularProgressIndicator())];
    final max = durationMs! / 1000.0;
    return [
      const SizedBox(height: 22),
      const Text('Hangi bölüm kalsın?', style: TextStyle(fontSize: 18, fontWeight: FontWeight.w700)),
      const SizedBox(height: 6),
      Text('Video uzunluğu: ${clock(max)}', style: const TextStyle(fontSize: 15, color: C.text2)),
      const SizedBox(height: 12),
      RangeSlider(
        values: trim!,
        min: 0,
        max: max,
        divisions: max > 1 ? max.floor() : null,
        labels: RangeLabels(clock(trim!.start), clock(trim!.end)),
        onChanged: (v) => setState(() => trim = v),
      ),
      Row(children: [
        _timeBox('Başlangıç', trim!.start),
        const SizedBox(width: 12),
        _timeBox('Bitiş', trim!.end),
      ]),
      const SizedBox(height: 10),
      Text('Yeni video ${clock(trim!.end - trim!.start)} sürecek.',
          style: const TextStyle(fontSize: 15, color: C.accent, fontWeight: FontWeight.w600)),
    ];
  }

  Widget _timeBox(String label, double sec) => Expanded(
        child: Box(
          padding: const EdgeInsets.symmetric(vertical: 10, horizontal: 14),
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Text(label, style: const TextStyle(fontSize: 13, color: C.text2)),
            Text(clock(sec), style: const TextStyle(fontSize: 22, fontWeight: FontWeight.w700)),
          ]),
        ),
      );
}

String clock(double sec) {
  final s = sec.round();
  final h = s ~/ 3600, m = (s % 3600) ~/ 60, r = s % 60;
  String two(int n) => n.toString().padLeft(2, '0');
  return h > 0 ? '$h:${two(m)}:${two(r)}' : '${two(m)}:${two(r)}';
}

String _msg(Object e) => e is PlatformException ? (e.message ?? e.code) : e.toString();

// ---------------------------------------------------------------- İlerleme ve sonuç

class RunScreen extends StatefulWidget {
  final Job job;
  const RunScreen({super.key, required this.job});
  @override
  State<RunScreen> createState() => _RunScreenState();
}

class _RunScreenState extends State<RunScreen> {
  late final String jobId = DateTime.now().microsecondsSinceEpoch.toString();
  StreamSubscription? _sub;
  double? value;
  List<FileItem>? outputs;
  String? error;
  String? savedTo;
  final saved = <String, String>{}; // çıktı yolu → İndirilenler adresi
  bool saving = false;

  @override
  void initState() {
    super.initState();
    _sub = Native.i.progress.where((e) => e.key == jobId).listen((e) => setState(() => value = e.value));
    _run();
  }

  @override
  void dispose() {
    _sub?.cancel();
    super.dispose();
  }

  Future<void> _run() async {
    try {
      final out = await Native.i.run(widget.job.method, jobId, widget.job.args);
      if (!mounted) return;
      setState(() => outputs = out);
      if (Store.i.autoSave) await _save();
      await Store.i.add(HistoryEntry(
        title: widget.job.title,
        outName: out.length == 1 ? out.first.name : '${out.length} dosya',
        inSize: widget.job.inputs.fold(0, (s, f) => s + f.size),
        outSize: out.fold(0, (s, f) => s + f.size),
        count: out.length,
        mime: widget.job.outMime,
        savedUri: out.length == 1 ? saved[out.first.path] : null,
        at: DateTime.now(),
      ));
    } catch (e) {
      if (!mounted) return;
      if (e is PlatformException && e.code == 'CANCELLED') {
        // İptal: seçenek ekranına geri dön
        Navigator.of(context).pop();
        toast(context, 'İptal edildi.');
        return;
      }
      setState(() => error = _msg(e));
    }
  }

  bool cancelling = false;

  Future<void> _cancel() async {
    if (cancelling) return;
    setState(() => cancelling = true);
    await Native.i.cancel(jobId);
  }

  Future<void> _askCancel() async {
    final ok = await showDialog<bool>(
      context: context,
      builder: (c) => AlertDialog(
        title: const Text('Dönüştürme durdurulsun mu?'),
        content: const Text('Yarım kalan dosya silinir.', style: TextStyle(fontSize: 16)),
        actions: [
          TextButton(onPressed: () => Navigator.pop(c, false), child: const Text('Devam et')),
          FilledButton(
            style: FilledButton.styleFrom(minimumSize: const Size(100, 44)),
            onPressed: () => Navigator.pop(c, true),
            child: const Text('Durdur'),
          ),
        ],
      ),
    );
    if (ok == true && mounted && running) await _cancel();
  }

  Future<void> _save() async {
    final out = outputs;
    if (out == null) return;
    setState(() => saving = true);
    try {
      final perFile = widget.job.outMime == '*/*'; // ZIP'ten çıkanlar: her dosya kendi türüyle, kendi klasörüne
      for (final f in out) {
        if (saved.containsKey(f.path)) continue;
        final r = await Native.i.save(
          FileItem(path: f.path, name: f.name, size: f.size, mime: perFile ? f.mime : widget.job.outMime),
          folder: perFile ? f.folder : '',
        );
        saved[f.path] = r['uri'] as String;
        savedTo = r['location'] as String?;
      }
    } catch (e) {
      if (mounted) toast(context, 'Kaydedilemedi: ${_msg(e)}');
    }
    if (mounted) setState(() => saving = false);
  }

  bool get running => outputs == null && error == null;

  @override
  Widget build(BuildContext context) => PopScope(
        canPop: !running,
        onPopInvokedWithResult: (didPop, _) {
          if (!didPop) _askCancel();
        },
        child: Scaffold(
          appBar: AppBar(
            automaticallyImplyLeading: !running,
            title: Text(running ? 'Dönüştürülüyor' : error != null ? 'Olmadı' : 'Hazır'),
          ),
          body: running ? _progressView() : error != null ? _errorView() : _resultView(),
        ),
      );

  Widget _progressView() {
    final pct = value == null ? null : (value! * 100).clamp(0, 100).round();
    return Padding(
      padding: const EdgeInsets.all(24),
      child: Column(mainAxisAlignment: MainAxisAlignment.center, children: [
        SizedBox(
          width: 140,
          height: 140,
          child: Stack(alignment: Alignment.center, children: [
            SizedBox.expand(
              child: CircularProgressIndicator(value: value, strokeWidth: 10, backgroundColor: C.border, color: C.accent),
            ),
            Text(pct == null ? '' : '%$pct', style: const TextStyle(fontSize: 30, fontWeight: FontWeight.w800)),
          ]),
        ),
        const SizedBox(height: 28),
        Text(widget.job.inputs.length == 1 ? widget.job.inputs.first.name : '${widget.job.inputs.length} dosya',
            textAlign: TextAlign.center, style: const TextStyle(fontSize: 18, fontWeight: FontWeight.w600)),
        const SizedBox(height: 8),
        const Text('Başka bir uygulamaya geçebilirsin; iş arka planda sürer, bitince bildirim gelir.',
            textAlign: TextAlign.center, style: TextStyle(fontSize: 15, color: C.text2)),
        const SizedBox(height: 24),
        // Kilit simgesi yazının hemen başında, satır kırılsa da birlikte durur
        const Text.rich(
          TextSpan(children: [
            WidgetSpan(
              alignment: PlaceholderAlignment.middle,
              child: Padding(padding: EdgeInsets.only(right: 6), child: Icon(Icons.lock_outline, size: 18, color: C.accent)),
            ),
            TextSpan(text: 'Telefonunda işleniyor, internete gönderilmiyor'),
          ]),
          textAlign: TextAlign.center,
          style: TextStyle(fontSize: 14, color: C.accent),
        ),
        const SizedBox(height: 32),
        OutlinedButton.icon(
          onPressed: cancelling ? null : _cancel,
          icon: const Icon(Icons.close),
          label: Text(cancelling ? 'Durduruluyor…' : 'Vazgeç'),
        ),
      ]),
    );
  }

  Widget _errorView() => Padding(
        padding: const EdgeInsets.all(24),
        child: Column(mainAxisAlignment: MainAxisAlignment.center, crossAxisAlignment: CrossAxisAlignment.stretch, children: [
          const Icon(Icons.error_outline, size: 72, color: C.danger),
          const SizedBox(height: 16),
          const Text('Dönüştürülemedi', textAlign: TextAlign.center, style: TextStyle(fontSize: 24, fontWeight: FontWeight.w800)),
          const SizedBox(height: 10),
          Text(error!, textAlign: TextAlign.center, style: const TextStyle(fontSize: 16, color: C.text2)),
          const SizedBox(height: 28),
          FilledButton(onPressed: () => Navigator.pop(context), child: const Text('Geri dön')),
        ]),
      );

  Widget _resultView() {
    final out = outputs!;
    final inSize = widget.job.inputs.fold<int>(0, (s, f) => s + f.size);
    final outSize = out.fold<int>(0, (s, f) => s + f.size);
    // "% daha küçük" yalnızca küçültme işlerinde anlamlı
    final shrinkJob = widget.job.method == 'pdfCompress' || widget.job.method == 'imageConvert' || widget.job.method == 'zip' ||
        (widget.job.method == 'mediaTransform' && (widget.job.args['height'] ?? 0) != 0);
    final smaller = shrinkJob && inSize > 0 && outSize < inSize;
    final pct = smaller ? ((1 - outSize / inSize) * 100).round() : 0;
    final allSaved = out.every((f) => saved.containsKey(f.path));
    return Column(children: [
      Expanded(child: ListView(
      padding: const EdgeInsets.fromLTRB(20, 8, 20, 16),
      children: [
        Container(
          padding: const EdgeInsets.all(20),
          decoration: BoxDecoration(color: C.accentTint, borderRadius: BorderRadius.circular(18)),
          child: Column(children: [
            const Icon(Icons.check_circle, size: 64, color: C.accent),
            const SizedBox(height: 10),
            Text(widget.job.title, textAlign: TextAlign.center, style: const TextStyle(fontSize: 24, fontWeight: FontWeight.w800)),
            const SizedBox(height: 6),
            Text(
              smaller
                  ? '${humanSize(inSize)} → ${humanSize(outSize)}  (%$pct daha küçük)'
                  : out.length > 1
                      ? '${out.length} dosya · ${humanSize(outSize)}'
                      : humanSize(outSize),
              textAlign: TextAlign.center,
              style: const TextStyle(fontSize: 17, color: C.accent, fontWeight: FontWeight.w700),
            ),
            if (allSaved && savedTo != null) ...[
              const SizedBox(height: 8),
              Text('$savedTo klasörüne kaydedildi', textAlign: TextAlign.center, style: const TextStyle(fontSize: 15, color: C.text2)),
            ],
          ]),
        ),
        if (widget.job.method == 'pdfToDocx') ...[
          const SizedBox(height: 12),
          Box(
            padding: const EdgeInsets.all(14),
            child: Text(
              '${out.first.ocrPages > 0 ? '${out.first.ocrPages} taranmış sayfa yazı tanımayla okundu; yazım hatası olabilir, kontrol et. ' : ''}'
              'Yazılar ve başlıklar aktarıldı. Tablolar, resimler ve yazı tipleri PDF\'teki gibi olmayabilir.',
              style: const TextStyle(fontSize: 15, color: C.text2, height: 1.35),
            ),
          ),
        ],
        const SizedBox(height: 16),
        for (final f in out.take(20))
          Padding(
            padding: const EdgeInsets.only(bottom: 8),
            child: Box(
              padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
              onTap: () => _open(f),
              child: Row(children: [
                Icon(_iconFor(widget.job.outMime == '*/*' ? f.mime : widget.job.outMime), color: C.text2),
                const SizedBox(width: 12),
                Expanded(
                  child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                    Text(f.name, maxLines: 2, overflow: TextOverflow.ellipsis, style: const TextStyle(fontSize: 16, fontWeight: FontWeight.w600)),
                    Text(
                      // ZIP'ten çıkanlarda alt klasörü de göster
                      widget.job.method == 'unzip' && f.folder.contains('/')
                          ? '${f.folder.substring(f.folder.indexOf('/') + 1)} · ${humanSize(f.size)}'
                          : humanSize(f.size),
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: const TextStyle(fontSize: 14, color: C.text2),
                    ),
                  ]),
                ),
                const Text('Aç', style: TextStyle(color: C.accent, fontWeight: FontWeight.w700, fontSize: 15)),
              ]),
            ),
          ),
        if (out.length > 20)
          Text('… ve ${out.length - 20} dosya daha', style: const TextStyle(fontSize: 15, color: C.text2)),
      ],
      )),
      // Düğmeler ekranın altında sabit: çok dosya çıksa da hep görünür
      SafeArea(
        top: false,
        child: Padding(
          padding: const EdgeInsets.fromLTRB(20, 8, 20, 12),
          child: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.stretch, children: [
            FilledButton.icon(
              onPressed: () => Native.i.share(out.map((f) => saved[f.path] ?? f.path).toList(), widget.job.outMime),
              icon: const Icon(Icons.share),
              label: Text(out.length > 1 ? 'Hepsini paylaş' : 'Paylaş'),
            ),
            const SizedBox(height: 10),
            if (!allSaved) ...[
              OutlinedButton.icon(
                onPressed: saving ? null : _save,
                icon: const Icon(Icons.download),
                label: Text(saving ? 'Kaydediliyor…' : 'İndirilenler\'e kaydet'),
              ),
              const SizedBox(height: 10),
            ],
            OutlinedButton(
              onPressed: () => Navigator.of(context).popUntil((r) => r.isFirst),
              child: const Text('Ana sayfaya dön'),
            ),
          ]),
        ),
      ),
    ]);
  }

  Future<void> _open(FileItem f) async {
    try {
      await Native.i.open(saved[f.path] ?? f.path, widget.job.outMime == '*/*' ? f.mime : widget.job.outMime);
    } catch (e) {
      if (mounted) toast(context, _msg(e));
    }
  }
}

IconData _iconFor(String mime) => mime == 'application/pdf'
    ? Icons.picture_as_pdf_outlined
    : mime == 'application/zip'
        ? Icons.folder_zip_outlined
        : mime.contains('wordprocessingml')
        ? Icons.description_outlined
        : mime.startsWith('image/')
        ? Icons.image_outlined
        : mime.startsWith('video/')
            ? Icons.movie_outlined
            : mime.startsWith('audio/')
                ? Icons.music_note_outlined
                : Icons.insert_drive_file_outlined;
