package com.ibrahim.cevirgen

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.print.PdfPrint
import android.print.PrintAttributes
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.File
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * DOCX → PDF, tamamen telefonda:
 *  1. Belge, internete kapalı bir WebView içinde docx-preview ile sayfa sayfa çizilir.
 *  2. Android'in yazdırma altyapısı bu sayfaları doğrudan PDF dosyasına yazar.
 * Belge hiçbir sunucuya gönderilmez.
 */
class DocxToPdf(private val ctx: Context) {

    private val main = Handler(Looper.getMainLooper())

    /** İşi iptal eder (convert çağrıldıktan sonra dolu olur). */
    var cancel: (() -> Unit)? = null

    /** Ana iş parçacığından çağrılmalı. */
    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    fun convert(input: File, out: File, done: (File?, String?) -> Unit) {
        val bytes = input.readBytes()
        if (bytes.size < 4 || bytes[0] != 'P'.code.toByte() || bytes[1] != 'K'.code.toByte()) {
            done(null, "Bu dosya .docx değil. Eski .doc belgeleri desteklenmiyor; Word'de .docx olarak kaydedip tekrar dene.")
            return
        }
        val web = WebView(ctx)
        var finished = false
        fun finish(file: File?, err: String?) {
            if (finished) return
            finished = true
            main.post {
                web.stopLoading()
                web.removeJavascriptInterface("Android")
                web.destroy()
                done(file, err)
            }
        }
        cancel = { out.delete(); finish(null, Jobs.CANCELLED) }

        web.settings.apply {
            javaScriptEnabled = true
            blockNetworkLoads = true          // belge dışarıya hiçbir istek atamaz
            allowFileAccess = false
            allowContentAccess = false
            domStorageEnabled = false
            loadsImagesAutomatically = true
        }
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: android.webkit.WebResourceRequest?) = true
        }
        val payload = Base64.encodeToString(bytes, Base64.NO_WRAP)

        web.addJavascriptInterface(object {
            @JavascriptInterface fun data(): String = payload

            @JavascriptInterface fun fail(msg: String) = finish(null, "Belge çizilemedi: $msg")

            @JavascriptInterface fun done(widthPx: Double, heightPx: Double, pages: Int) {
                main.post {
                    try {
                        val wMils = (widthPx / 96.0 * 1000).roundToInt().coerceIn(1000, 50000)
                        val hMils = ((ceil(heightPx) + 2) / 96.0 * 1000).roundToInt().coerceIn(1000, 50000)
                        val attrs = PrintAttributes.Builder()
                            .setMediaSize(PrintAttributes.MediaSize("cevirgen_docx", "Belge", wMils, hMils))
                            .setResolution(PrintAttributes.Resolution("pdf", "pdf", 300, 300))
                            .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
                            .setColorMode(PrintAttributes.COLOR_MODE_COLOR)
                            .build()
                        val adapter = web.createPrintDocumentAdapter(out.nameWithoutExtension)
                        PdfPrint(attrs).print(adapter, out) { ok, err ->
                            if (ok && out.length() > 0) finish(out, null) else finish(null, err ?: "PDF oluşturulamadı")
                        }
                    } catch (e: Exception) {
                        finish(null, e.message ?: "PDF oluşturulamadı")
                    }
                }
            }
        }, "Android")

        main.postDelayed({ finish(null, "Belge çok büyük ya da karmaşık; dönüştürme zaman aşımına uğradı.") }, 120_000)
        web.loadDataWithBaseURL("https://cevirgen.local/", page(), "text/html", "utf-8", null)
    }

    private fun asset(name: String): String =
        ctx.assets.open("flutter_assets/assets/docx/$name").bufferedReader().use { it.readText() }

    private fun page(): String = """
<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width">
<style id="pagestyle"></style>
<style>
html,body{margin:0;padding:0;background:#fff}
.docx-wrapper{background:#fff!important;padding:0!important;display:block!important}
.docx-wrapper>section.docx{box-shadow:none!important;margin:0!important;page-break-after:always;break-after:page;overflow:hidden}
.docx-wrapper>section.docx:last-child{page-break-after:auto;break-after:auto}
</style>
<script>${asset("jszip.min.js")}</script>
<script>${asset("docx-preview.min.js")}</script>
</head><body><div id="c"></div>
<script>
var W = 'http://schemas.openxmlformats.org/wordprocessingml/2006/main';
var TOK_PAGE = '⁣P⁣', TOK_NUM = '⁣N⁣';

/* Sayfa numarası alanlarını işaretle, çerçeve hizasını paragraf hizasına çevir. */
function fixXml(xml) {
  var doc = new DOMParser().parseFromString(xml, 'application/xml');
  if (doc.getElementsByTagName('parsererror').length) return xml;
  var stack = [];
  function kind(instr) {
    var s = (instr || '').trim().toUpperCase();
    if (/^NUMPAGES\b/.test(s) || /^SECTIONPAGES\b/.test(s)) return TOK_NUM;
    if (/^PAGE\b/.test(s)) return TOK_PAGE;
    return null;
  }
  var all = doc.getElementsByTagNameNS(W, '*');
  for (var i = 0; i < all.length; i++) {
    var el = all[i], n = el.localName;
    if (n === 'fldChar') {
      var t = el.getAttributeNS(W, 'fldCharType');
      if (t === 'begin') stack.push({ instr: '', sep: false, done: false });
      else if (t === 'separate' && stack.length) stack[stack.length - 1].sep = true;
      else if (t === 'end') stack.pop();
    } else if (n === 'instrText' && stack.length) {
      stack[stack.length - 1].instr += el.textContent;
    } else if (n === 't' && stack.length) {
      var f = stack[stack.length - 1], k = f.sep ? kind(f.instr) : null;
      if (k) { el.textContent = f.done ? '' : k; f.done = true; }
    } else if (n === 'fldSimple') {
      var k2 = kind(el.getAttributeNS(W, 'instr'));
      if (k2) {
        var ts = el.getElementsByTagNameNS(W, 't');
        for (var j = 0; j < ts.length; j++) ts[j].textContent = j === 0 ? k2 : '';
        if (!ts.length) {
          var r = doc.createElementNS(W, 'w:r'), tt = doc.createElementNS(W, 'w:t');
          tt.textContent = k2; r.appendChild(tt); el.appendChild(r);
        }
      }
    } else if (n === 'framePr') {
      var a = el.getAttributeNS(W, 'xAlign');
      var pPr = el.parentNode;
      if ((a === 'right' || a === 'center') && pPr && !pPr.getElementsByTagNameNS(W, 'jc').length) {
        var jc = doc.createElementNS(W, 'w:jc');
        jc.setAttributeNS(W, 'w:val', a);
        pPr.appendChild(jc);
      }
    }
  }
  return new XMLSerializer().serializeToString(doc);
}

function kids(el, name) {
  var out = [];
  for (var c = el ? el.firstElementChild : null; c; c = c.nextElementSibling) if (c.localName === name && c.namespaceURI === W) out.push(c);
  return out;
}
function kid(el, name) { return kids(el, name)[0] || null; }
function val(el) { return el ? el.getAttributeNS(W, 'val') : null; }

/* styles.xml + numbering.xml: stil zinciri, liste girintileri, "aynı stilde boşluk bırakma" bilgisi */
function readStyles(stylesXml, numXml) {
  var ctx = { styles: {}, def: 'Normal', numToAbs: {}, absInd: {} };
  if (stylesXml) {
    var sd = new DOMParser().parseFromString(stylesXml, 'application/xml');
    var ss = sd.getElementsByTagNameNS(W, 'style');
    for (var i = 0; i < ss.length; i++) {
      var s = ss[i];
      if (s.getAttributeNS(W, 'type') !== 'paragraph') continue;
      var id = s.getAttributeNS(W, 'styleId');
      var pPr = kid(s, 'pPr'), numPr = kid(pPr, 'numPr');
      ctx.styles[id] = {
        basedOn: val(kid(s, 'basedOn')),
        numId: val(kid(numPr, 'numId')), ilvl: val(kid(numPr, 'ilvl')),
        ctxSp: !!kid(pPr, 'contextualSpacing') && val(kid(pPr, 'contextualSpacing')) !== '0'
      };
      if (s.getAttributeNS(W, 'default') === '1') ctx.def = id;
    }
  }
  if (numXml) {
    var nd = new DOMParser().parseFromString(numXml, 'application/xml');
    var abs = nd.getElementsByTagNameNS(W, 'abstractNum');
    for (var a = 0; a < abs.length; a++) {
      var aid = abs[a].getAttributeNS(W, 'abstractNumId'), lv = kids(abs[a], 'lvl');
      ctx.absInd[aid] = {};
      for (var l = 0; l < lv.length; l++) {
        var ind = kid(kid(lv[l], 'pPr'), 'ind');
        if (ind) ctx.absInd[aid][lv[l].getAttributeNS(W, 'ilvl')] = ind;
      }
    }
    var nums = nd.getElementsByTagNameNS(W, 'num');
    for (var n = 0; n < nums.length; n++) ctx.numToAbs[nums[n].getAttributeNS(W, 'numId')] = val(kid(nums[n], 'abstractNumId'));
  }
  return ctx;
}
function styleProp(ctx, id, key) {
  for (var guard = 0; id && ctx.styles[id] && guard < 20; guard++) {
    var v = ctx.styles[id][key];
    if (v) return v;
    id = ctx.styles[id].basedOn;
  }
  return null;
}

/* Liste paragraflarına girintiyi yaz; "aynı stildeki paragraflar arasında boşluk bırakma" ayarını uygula. */
function fixLists(xml, ctx) {
  var doc = new DOMParser().parseFromString(xml, 'application/xml');
  if (doc.getElementsByTagName('parsererror').length) return xml;
  function pPrOf(p) {
    var pPr = kid(p, 'pPr');
    if (!pPr) { pPr = doc.createElementNS(W, 'w:pPr'); p.insertBefore(pPr, p.firstChild); }
    return pPr;
  }
  function setSpacing(p, attr) {
    var pPr = pPrOf(p), sp = kid(pPr, 'spacing');
    if (!sp) { sp = doc.createElementNS(W, 'w:spacing'); pPr.appendChild(sp); }
    sp.setAttributeNS(W, 'w:' + attr, '0');
  }
  var ps = doc.getElementsByTagNameNS(W, 'p'), info = [];
  for (var i = 0; i < ps.length; i++) {
    var p = ps[i], pPr = kid(p, 'pPr');
    var sid = val(kid(pPr, 'pStyle')) || ctx.def;
    var numPr = kid(pPr, 'numPr');
    var numId = val(kid(numPr, 'numId')) || styleProp(ctx, sid, 'numId');
    var ilvl = val(kid(numPr, 'ilvl')) || styleProp(ctx, sid, 'ilvl') || '0';
    if (numId && numId !== '0' && !kid(pPr, 'ind')) {
      var lv = ctx.absInd[ctx.numToAbs[numId]], ind = lv && lv[ilvl];
      if (ind) pPrOf(p).appendChild(doc.importNode(ind, true));
    }
    var cs = kid(pPr, 'contextualSpacing');
    info.push({ p: p, sid: sid, ctxSp: cs ? val(cs) !== '0' : !!styleProp(ctx, sid, 'ctxSp') });
  }
  for (var j = 0; j + 1 < info.length; j++) {
    var a = info[j], b = info[j + 1];
    if (a.ctxSp && b.ctxSp && a.sid === b.sid && a.p.nextElementSibling === b.p) { setSpacing(a.p, 'after'); setSpacing(b.p, 'before'); }
  }
  return new XMLSerializer().serializeToString(doc);
}

/* İki sütunlu bölümler: docx-preview sütunları CSS ile dengeler ve "sütun sonu"nu yok sayar; içerik
   sütunlar ve sayfalar arasında kayar. Sütun sonu içeren bölümü her sayfa için iki hücreli, kenarlıksız
   bir tabloya çevir (sol sütun | boşluk | sağ sütun), sayfa geçişlerine sayfa sonu koy. */
function fixColumns(xml) {
  var doc = new DOMParser().parseFromString(xml, 'application/xml');
  if (doc.getElementsByTagName('parsererror').length) return xml;
  var body = doc.getElementsByTagNameNS(W, 'body')[0];
  if (!body) return xml;
  function el(n) { return doc.createElementNS(W, 'w:' + n); }
  function attr(e, n, v) { e.setAttributeNS(W, 'w:' + n, String(v)); return e; }
  function num(e, n) { var v = e ? parseInt(e.getAttributeNS(W, n), 10) : NaN; return isNaN(v) ? null : v; }
  function isColBr(n) { return n.localName === 'br' && n.namespaceURI === W && n.getAttributeNS(W, 'type') === 'column'; }
  function isPageBr(n) { return n.localName === 'br' && n.namespaceURI === W && n.getAttributeNS(W, 'type') === 'page'; }
  function hasColBr(e) {
    var brs = e.getElementsByTagNameNS(W, 'br');
    for (var k = 0; k < brs.length; k++) if (isColBr(brs[k])) return true;
    return false;
  }
  var blocks = [];
  for (var c = body.firstElementChild; c; c = c.nextElementSibling) blocks.push(c);
  var start = 0, changed = false;
  for (var i = 0; i < blocks.length; i++) {
    var b = blocks[i], sp = null;
    if (b.localName === 'p') sp = kid(kid(b, 'pPr'), 'sectPr');
    else if (b.localName === 'sectPr') sp = b;
    if (!sp) continue;
    var sec = blocks.slice(start, b.localName === 'p' ? i + 1 : i);
    start = i + 1;
    var cols = kid(sp, 'cols');
    if (!cols || num(cols, 'num') !== 2 || !sec.some(hasColBr)) continue;

    // sütun genişlikleri
    var pgSz = kid(sp, 'pgSz'), pgMar = kid(sp, 'pgMar');
    var total = (num(pgSz, 'w') || 11906) - (num(pgMar, 'left') || 1440) - (num(pgMar, 'right') || 1440);
    var colEls = kids(cols, 'col'), space = num(cols, 'space');
    if (space == null) space = 720;
    var w1, w2;
    if (colEls.length === 2 && num(colEls[0], 'w')) {
      w1 = num(colEls[0], 'w');
      if (num(colEls[0], 'space') != null) space = num(colEls[0], 'space');
      w2 = num(colEls[1], 'w') || (total - w1 - space);
    } else { w1 = w2 = Math.floor((total - space) / 2); }

    // bölüm sonu bilgisini ayrı, boş bir paragrafa taşı (tek sütun)
    var holder = null;
    if (b.localName === 'p') {
      holder = el('p');
      var hp = el('pPr');
      holder.appendChild(hp);
      hp.appendChild(sp);
    }
    sp.removeChild(cols);

    // içeriği sütun sonlarından parçala: [sayfa1 sol, sayfa1 sağ, sayfa2 sol, ...]
    var chunks = [[]];
    sec.forEach(function (e) {
      if (e.localName !== 'p') { if (e.localName !== 'bookmarkEnd') chunks[chunks.length - 1].push(e.cloneNode(true)); return; }
      var pPr = kid(e, 'pPr'), cur = [], broke = false;
      function newRun(r) { var n = r.cloneNode(false), rp = kid(r, 'rPr'); if (rp) n.appendChild(rp.cloneNode(true)); return n; }
      function flush(force) {
        if (cur.length || force) {
          var np = el('p');
          if (pPr) np.appendChild(pPr.cloneNode(true));
          cur.forEach(function (n) { np.appendChild(n); });
          chunks[chunks.length - 1].push(np);
        }
        cur = [];
      }
      for (var ch = e.firstElementChild; ch; ch = ch.nextElementSibling) {
        if (ch === pPr) continue;
        if (!(ch.localName === 'r' && kids(ch, 'br').some(function (x) { return isColBr(x) || isPageBr(x); }))) { cur.push(ch.cloneNode(true)); continue; }
        var piece = newRun(ch), has = false;
        for (var g = ch.firstElementChild; g; g = g.nextElementSibling) {
          if (g.localName === 'rPr') continue;
          if (isColBr(g) || isPageBr(g)) {
            if (has) cur.push(piece);
            flush(false);
            // sütun sonu: sonraki sütun; sayfa sonu: sonraki sayfanın sol sütunu
            if (isColBr(g)) chunks.push([]);
            else { if ((chunks.length - 1) % 2 === 0) chunks.push([]); chunks.push([]); }
            broke = true;
            piece = newRun(ch); has = false;
          } else { piece.appendChild(g.cloneNode(true)); has = true; }
        }
        if (has) cur.push(piece);
      }
      flush(!broke);
    });

    function cell(w, content) {
      var tc = el('tc'), tcPr = el('tcPr');
      tcPr.appendChild(attr(attr(el('tcW'), 'w', w), 'type', 'dxa'));
      tc.appendChild(tcPr);
      content.forEach(function (n) { tc.appendChild(n); });
      if (!content.length || content[content.length - 1].localName !== 'p') tc.appendChild(el('p'));
      return tc;
    }
    function table(L, R) {
      var tbl = el('tbl'), pr = el('tblPr');
      pr.appendChild(attr(attr(el('tblW'), 'w', w1 + space + w2), 'type', 'dxa'));
      var bd = el('tblBorders');
      ['top', 'left', 'bottom', 'right', 'insideH', 'insideV'].forEach(function (s) { bd.appendChild(attr(el(s), 'val', 'nil')); });
      pr.appendChild(bd);
      pr.appendChild(attr(el('tblLayout'), 'type', 'fixed'));
      var mar = el('tblCellMar');
      ['top', 'left', 'bottom', 'right'].forEach(function (s) { mar.appendChild(attr(attr(el(s), 'w', 0), 'type', 'dxa')); });
      pr.appendChild(mar);
      tbl.appendChild(pr);
      var grid = el('tblGrid');
      [w1, space, w2].forEach(function (w) { grid.appendChild(attr(el('gridCol'), 'w', w)); });
      tbl.appendChild(grid);
      var tr = el('tr');
      tr.appendChild(cell(w1, L)); tr.appendChild(cell(space, [])); tr.appendChild(cell(w2, R));
      tbl.appendChild(tr);
      return tbl;
    }

    var anchor = sec.length ? sec[0] : b;
    var leadBreak = false;
    for (var k = 0; k < chunks.length; k += 2) {
      // bölüm sayfa sonuyla başlıyorsa ilk (boş) sayfa çifti tablo yerine sayfa sonu olur
      if (k === 0 && !chunks[0].length && !(chunks[1] || []).length && chunks.length > 2) { leadBreak = true; continue; }
      if (k > 0 || leadBreak) {
        var pb = el('p'), rr = el('r');
        rr.appendChild(attr(el('br'), 'type', 'page'));
        pb.appendChild(rr);
        body.insertBefore(pb, anchor);
      }
      body.insertBefore(table(chunks[k], chunks[k + 1] || []), anchor);
    }
    if (holder) body.insertBefore(holder, anchor);
    sec.forEach(function (e) { if (e.parentNode === body) body.removeChild(e); });
    changed = true;
  }
  if (!changed) return xml;

  // Artık gereksiz kalan "devamlı" bölüm geçişlerini kaldır (aynı sayfa ayarı, tek sütun, üst/alt bilgisi yok):
  // docx-preview her bölümü ayrı blok çizip araya boşluk koyuyor.
  var fin = kid(body, 'sectPr');
  function same(a, b2, tag, keys) {
    var x = kid(a, tag), y = kid(b2, tag);
    return keys.every(function (k2) { return num(x, k2) === num(y, k2); });
  }
  var paras = kids(body, 'p');
  paras.forEach(function (p) {
    var pPr = kid(p, 'pPr'), s = kid(pPr, 'sectPr');
    if (!s || !fin) return;
    var cl = kid(s, 'cols');
    if (val(kid(s, 'type')) !== 'continuous' || (cl && (num(cl, 'num') || 1) > 1)) return;
    if (kid(s, 'headerReference') || kid(s, 'footerReference')) return;
    if (!same(s, fin, 'pgSz', ['w', 'h']) || !same(s, fin, 'pgMar', ['left', 'right'])) return;
    pPr.removeChild(s);
    var onlyPPr = true;
    for (var ch2 = p.firstElementChild; ch2; ch2 = ch2.nextElementSibling) if (ch2 !== pPr) onlyPPr = false;
    if (onlyPPr) body.removeChild(p);
  });
  return new XMLSerializer().serializeToString(doc);
}

async function prepare(buf) {
  try {
    var zip = await JSZip.loadAsync(buf);
    var ctx = readStyles(zip.file('word/styles.xml') ? await zip.file('word/styles.xml').async('string') : null,
                         zip.file('word/numbering.xml') ? await zip.file('word/numbering.xml').async('string') : null);
    var names = Object.keys(zip.files).filter(function (n) { return /^word\/(header|footer)\d*\.xml$/.test(n) || n === 'word/document.xml'; });
    for (var i = 0; i < names.length; i++) {
      var x = await zip.file(names[i]).async('string');
      if (x.indexOf('PAGE') >= 0 || x.indexOf('framePr') >= 0) x = fixXml(x);
      try { x = fixLists(x, ctx); } catch (e2) {}
      if (names[i] === 'word/document.xml' && x.indexOf('"column"') >= 0) { try { x = fixColumns(x); } catch (e3) {} }
      zip.file(names[i], x);
    }
    return await zip.generateAsync({ type: 'arraybuffer' });
  } catch (e) { return buf; }
}

/* Symbol / Wingdings yazı tipindeki madde işaretlerini her yazı tipinde olan karakterlere çevir. */
var SYM = { '': '•', '': '▪', '': '➢', '': '❖', '': '✓',
            '': '□', '': '□', '': '→', '': '○', '': '–' };
function fixBullets() {
  document.querySelectorAll('style').forEach(function (st) {
    var t = st.textContent;
    var u = t.replace(/[-]/g, function (c) { return SYM[c] || '•'; })
             .replace(/\\f0([0-9a-fA-F]{2})\s?/g, function (m, h) { var c = String.fromCharCode(0xF000 + parseInt(h, 16)); return SYM[c] || '•'; });
    if (u !== t) st.textContent = u;
  });
  var w = document.createTreeWalker(document.getElementById('c'), NodeFilter.SHOW_TEXT);
  var n;
  while ((n = w.nextNode())) {
    if (/[-]/.test(n.nodeValue)) n.nodeValue = n.nodeValue.replace(/[-]/g, function (c) { return SYM[c] || '•'; });
  }
}

/* İşaretlenen alanlara her sayfanın kendi numarasını yaz. */
function fillPageNumbers() {
  var secs = document.querySelectorAll('section.docx');
  secs.forEach(function (sec, i) {
    var w = document.createTreeWalker(sec, NodeFilter.SHOW_TEXT), n;
    while ((n = w.nextNode())) {
      if (n.nodeValue.indexOf('⁣') >= 0)
        n.nodeValue = n.nodeValue.split(TOK_PAGE).join(String(i + 1)).split(TOK_NUM).join(String(secs.length));
    }
  });
}

(async function(){
  try {
    var b64 = Android.data();
    var bin = atob(b64);
    var bytes = new Uint8Array(bin.length);
    for (var i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
    var prepared = await prepare(bytes.buffer);
    await docx.renderAsync(prepared, document.getElementById('c'), null, {
      inWrapper: true, breakPages: true, ignoreLastRenderedPageBreak: false,
      experimental: true, useBase64URL: true, ignoreWidth: false, ignoreHeight: false,
      renderHeaders: true, renderFooters: true, renderFootnotes: true, renderEndnotes: true
    });
    fixBullets();
    fillPageNumbers();
    if (document.fonts && document.fonts.ready) await document.fonts.ready;
    var imgs = Array.prototype.slice.call(document.images);
    await Promise.all(imgs.map(function(im){ return im.complete ? 0 : new Promise(function(r){ im.onload = im.onerror = r; }); }));
    var s = document.querySelector('section.docx');
    if (!s) throw new Error('Belgede sayfa bulunamadı');
    var w = parseFloat(s.style.width) ? s.getBoundingClientRect().width : 794;
    var h = parseFloat(s.style.minHeight) ? parseFloat(getComputedStyle(s).minHeight) : 1123;
    document.querySelectorAll('section.docx').forEach(function(sec){ sec.style.minHeight = h + 'px'; });
    document.getElementById('pagestyle').textContent = '@page{size:' + w + 'px ' + (Math.ceil(h) + 2) + 'px;margin:0}';
    Android.done(w, h, document.querySelectorAll('section.docx').length);
  } catch (e) {
    Android.fail(String((e && e.message) || e));
  }
})();
</script></body></html>
""".trimIndent()
}
