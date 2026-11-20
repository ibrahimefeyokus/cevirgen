import 'package:flutter/material.dart';

/// Tasarım tuvalindeki renkler.
class C {
  static const bg = Color(0xFFF6F7F5);
  static const card = Colors.white;
  static const accent = Color(0xFF0F6E56);
  static const accentTint = Color(0xFFE3F2EC);
  static const text = Color(0xFF15171A);
  static const text2 = Color(0xFF5B6068);
  static const border = Color(0xFFE4E6E2);
  static const danger = Color(0xFFB4442A);
}

/// Dosya türü kutucuğu renkleri (arka plan, simge).
class Tint {
  final Color bg;
  final Color fg;
  const Tint(this.bg, this.fg);
  static const pdf = Tint(Color(0xFFFDEBE6), Color(0xFFB4442A));
  static const word = Tint(Color(0xFFE6EEFB), Color(0xFF2457A6));
  static const photo = Tint(Color(0xFFFFF3D6), Color(0xFF8A5A00));
  static const video = Tint(Color(0xFFEEEAFB), Color(0xFF5940B0));
  static const zip = Tint(Color(0xFFE8EEF0), Color(0xFF3D5560));
}

ThemeData buildTheme() {
  final base = ThemeData(
    useMaterial3: true,
    colorScheme: ColorScheme.fromSeed(seedColor: C.accent, primary: C.accent, surface: C.bg),
    scaffoldBackgroundColor: C.bg,
  );
  return base.copyWith(
    textTheme: base.textTheme.apply(bodyColor: C.text, displayColor: C.text),
    appBarTheme: const AppBarTheme(
      backgroundColor: C.bg,
      foregroundColor: C.text,
      elevation: 0,
      scrolledUnderElevation: 0,
      titleTextStyle: TextStyle(fontSize: 20, fontWeight: FontWeight.w700, color: C.text),
    ),
    filledButtonTheme: FilledButtonThemeData(
      style: FilledButton.styleFrom(
        backgroundColor: C.accent,
        minimumSize: const Size.fromHeight(56),
        textStyle: const TextStyle(fontSize: 17, fontWeight: FontWeight.w700),
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(14)),
      ),
    ),
    outlinedButtonTheme: OutlinedButtonThemeData(
      style: OutlinedButton.styleFrom(
        foregroundColor: C.text,
        minimumSize: const Size.fromHeight(52),
        side: const BorderSide(color: C.border, width: 1.5),
        textStyle: const TextStyle(fontSize: 16, fontWeight: FontWeight.w600),
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(14)),
      ),
    ),
    navigationBarTheme: NavigationBarThemeData(
      backgroundColor: Colors.white,
      indicatorColor: C.accentTint,
      height: 68,
      labelTextStyle: WidgetStateProperty.all(const TextStyle(fontSize: 13, fontWeight: FontWeight.w600)),
    ),
  );
}

/// Beyaz, ince kenarlı kart.
class Box extends StatelessWidget {
  final Widget child;
  final EdgeInsets padding;
  final VoidCallback? onTap;
  final Color? color;
  final Color? borderColor;
  const Box({super.key, required this.child, this.padding = const EdgeInsets.all(16), this.onTap, this.color, this.borderColor});

  @override
  Widget build(BuildContext context) => Material(
        color: color ?? C.card,
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(16),
          side: BorderSide(color: borderColor ?? C.border, width: borderColor == null ? 1 : 2),
        ),
        clipBehavior: Clip.antiAlias,
        child: InkWell(onTap: onTap, child: Padding(padding: padding, child: child)),
      );
}

class IconTile extends StatelessWidget {
  final IconData icon;
  final Tint tint;
  final double size;
  const IconTile(this.icon, this.tint, {super.key, this.size = 48});

  @override
  Widget build(BuildContext context) => Container(
        width: size,
        height: size,
        decoration: BoxDecoration(color: tint.bg, borderRadius: BorderRadius.circular(size * .28)),
        child: Icon(icon, color: tint.fg, size: size * .52),
      );
}

void toast(BuildContext context, String msg) {
  ScaffoldMessenger.of(context)
    ..hideCurrentSnackBar()
    ..showSnackBar(SnackBar(content: Text(msg, style: const TextStyle(fontSize: 15)), behavior: SnackBarBehavior.floating));
}
