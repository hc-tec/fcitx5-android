/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.popup

/**
 * based on
 * [fcitx5/src/im/keyboard/longpress.cpp](https://github.com/fcitx/fcitx5/blob/5.0.18/src/im/keyboard/longpress.cpp#L15)
 */
val PopupPreset: Map<String, Array<String>> = hashMapOf(
    //
    // Latin
    //
    // Keep Latin long-press options focused on common Chinese IME usage:
    // digit or symbol from the key, plus case switching.
    "q" to arrayOf("1", "Q"),
    "w" to arrayOf("2", "W"),
    "e" to arrayOf("3", "E"),
    "r" to arrayOf("4", "R"),
    "t" to arrayOf("5", "T"),
    "y" to arrayOf("6", "Y"),
    "u" to arrayOf("7", "U"),
    "i" to arrayOf("8", "I"),
    "o" to arrayOf("9", "O"),
    "p" to arrayOf("0", "P"),
    "a" to arrayOf("@", "A"),
    "s" to arrayOf("*", "S"),
    "d" to arrayOf("+", "D"),
    "f" to arrayOf("-", "F"),
    "g" to arrayOf("=", "G"),
    "h" to arrayOf("/", "H"),
    "j" to arrayOf("#", "J"),
    "k" to arrayOf("(", "K"),
    "l" to arrayOf(")", "L"),
    "z" to arrayOf("'", "Z"),
    "x" to arrayOf(":", "X"),
    "c" to arrayOf("\"", "C"),
    "v" to arrayOf("?", "V"),
    "b" to arrayOf("!", "B"),
    "n" to arrayOf("~", "N"),
    "m" to arrayOf("\\", "M"),
    //
    // Upper case Latin
    //
    "Q" to arrayOf("1", "q"),
    "W" to arrayOf("2", "w"),
    "E" to arrayOf("3", "e"),
    "R" to arrayOf("4", "r"),
    "T" to arrayOf("5", "t"),
    "Y" to arrayOf("6", "y"),
    "U" to arrayOf("7", "u"),
    "I" to arrayOf("8", "i"),
    "O" to arrayOf("9", "o"),
    "P" to arrayOf("0", "p"),
    "A" to arrayOf("@", "a"),
    "S" to arrayOf("*", "s"),
    "D" to arrayOf("+", "d"),
    "F" to arrayOf("-", "f"),
    "G" to arrayOf("=", "g"),
    "H" to arrayOf("/", "h"),
    "J" to arrayOf("#", "j"),
    "K" to arrayOf("(", "k"),
    "L" to arrayOf(")", "l"),
    "Z" to arrayOf("'", "z"),
    "X" to arrayOf(":", "x"),
    "C" to arrayOf("\"", "c"),
    "V" to arrayOf("?", "v"),
    "B" to arrayOf("!", "b"),
    "N" to arrayOf("~", "n"),
    "M" to arrayOf("\\", "m"),
    //
    // Upper case Cyrillic
    //
    "г" to arrayOf("ғ"),
    "е" to arrayOf("ё"),      // this in fact NOT the same E as before
    "и" to arrayOf("ӣ", "і"), // і is not i
    "й" to arrayOf("ј"),      // ј is not j
    "к" to arrayOf("қ", "ҝ"),
    "н" to arrayOf("ң", "һ"), // һ is not h
    "о" to arrayOf("ә", "ө"),
    "ч" to arrayOf("ҷ", "ҹ"),
    "ь" to arrayOf("ъ"),
    //
    // Cyrillic
    //
    "Г" to arrayOf("Ғ"),
    "Е" to arrayOf("Ё"),      // This In Fact Not The Same E As Before
    "И" to arrayOf("Ӣ", "І"), // І is sot I
    "Й" to arrayOf("Ј"),      // Ј is sot J
    "К" to arrayOf("Қ", "Ҝ"),
    "Н" to arrayOf("Ң", "Һ"), // Һ is not H
    "О" to arrayOf("Ә", "Ө"),
    "Ч" to arrayOf("Ҷ", "Ҹ"),
    "Ь" to arrayOf("Ъ"),
    //
    // Arabic
    //
    // This renders weirdly in text editors, but is valid code.
    "ا" to arrayOf("أ", "إ", "آ", "ء"),
    "ب" to arrayOf("پ"),
    "ج" to arrayOf("چ"),
    "ز" to arrayOf("ژ"),
    "ف" to arrayOf("ڤ"),
    "ك" to arrayOf("گ"),
    "ل" to arrayOf("لا"),
    "ه" to arrayOf("ه"),
    "و" to arrayOf("ؤ"),
    //
    // Hebrew
    //
    // Likewise, this will render oddly, but is still valid code.
    "ג" to arrayOf("ג׳"),
    "ז" to arrayOf("ז׳"),
    "ח" to arrayOf("ח׳"),
    "צ׳" to arrayOf("צ׳"),
    "ת" to arrayOf("ת׳"),
    "י" to arrayOf("ײַ"),
    "י" to arrayOf("ײ"),
    "ח" to arrayOf("ױ"),
    "ו" to arrayOf("װ"),
    //
    // Numbers
    //
    "0" to arrayOf("∅", "ⁿ", "⁰"),
    "1" to arrayOf("¹", "½", "⅓", "¼", "⅕", "⅙", "⅐", "⅛", "⅑", "⅒"),
    "2" to arrayOf("²", "⅖", "⅔"),
    "3" to arrayOf("³", "⅗", "¾", "⅜"),
    "4" to arrayOf("⁴", "⅘", "⅝", "⅚"),
    "5" to arrayOf("⁵", "⅝", "⅚"),
    "6" to arrayOf("⁶"),
    "7" to arrayOf("⁷", "⅞"),
    "8" to arrayOf("⁸"),
    "9" to arrayOf("⁹"),
    //
    // Punctuation
    //
    "." to arrayOf(",", "?", "!", ":", ";", "_", "%", "$", "^", "&"),
    "-" to arrayOf("—", "–", "·"),
    "?" to arrayOf("¿", "‽"),
    "'" to arrayOf("‘", "’", "‚", "›", "‹"),
    "!" to arrayOf("¡"),
    "\"" to arrayOf("“", "”", "„", "»", "«"),
    "/" to arrayOf("÷"),
    "#" to arrayOf("№"),
    "%" to arrayOf("‰", "℅"),
    "^" to arrayOf("↑", "↓", "←", "→"),
    "+" to arrayOf("±"),
    "<" to arrayOf("≤", "«", "‹", "⟨"),
    "=" to arrayOf("∞", "≠", "≈"),
    ">" to arrayOf("≥", "»", "›", "⟩"),
    "°" to arrayOf("′", "″", "‴"),
    //
    // Currency
    //
    "$" to arrayOf("¢", "€", "£", "¥", "₹", "₽", "₺", "₩", "₱", "₿"),
)
