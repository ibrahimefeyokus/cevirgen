import 'package:cevirgen/actions.dart';
import 'package:cevirgen/flow.dart';
import 'package:cevirgen/native.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('sayfa aralığı ayrıştırma', () {
    expect(parsePages('1-3, 5', 10), [0, 1, 2, 4]);
    expect(parsePages('3-1', 5), [2, 1, 0]);
    expect(parsePages('2 4', 5), [1, 3]);
    expect(parsePages('0', 5), isNull);
    expect(parsePages('6', 5), isNull);
    expect(parsePages('1-', 5), isNull);
    expect(parsePages('', 5), isNull);
  });

  test('dosya türü tanıma', () {
    FileItem f(String n, String m) => FileItem(path: '/x/$n', name: n, size: 1, mime: m);
    expect(kindOf(f('a.pdf', 'application/pdf')), Kind.pdf);
    expect(kindOf(f('a.docx', 'application/octet-stream')), Kind.docx);
    expect(kindOf(f('a.HEIC', 'image/heic')), Kind.image);
    expect(kindOf(f('a.mp4', 'video/mp4')), Kind.video);
    expect(kindOf(f('a.m4a', 'audio/mp4')), Kind.audio);
    expect(kindOf(f('a.zip', 'application/zip')), Kind.archive);
    expect(kindOf(f('a.txt', 'text/plain')), isNull);
    // ZIP'ten çıkan dosyanın klasörü
    final x = FileItem(path: '/o/arsiv/alt/b.pdf', name: 'b.pdf', size: 1, mime: 'application/pdf', rel: 'arsiv/alt/b.pdf');
    expect(x.folder, 'arsiv/alt');
    expect(FileItem(path: '/o/c.pdf', name: 'c.pdf', size: 1, mime: 'application/pdf', rel: 'c.pdf').folder, '');
    expect(kindOfAll([f('a.jpg', 'image/jpeg'), f('b.png', 'image/png'), f('c.pdf', 'application/pdf')]), Kind.image);
  });

  test('biçimler', () {
    expect(humanSize(1536), '2 KB');
    expect(humanSize(5 * 1024 * 1024 + 300000), '5,3 MB');
    expect(clock(75), '01:15');
    expect(clock(3725), '1:02:05');
  });
}
