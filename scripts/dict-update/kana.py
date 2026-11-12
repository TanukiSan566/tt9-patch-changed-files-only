"""Hiragana reading -> TT9 Japanese romaji (the transcription convention used by ja-romaji.csv)."""
import re

_BASE = {
 'あ':'A','い':'I','う':'U','え':'E','お':'O','ゔ':'VU',
 'か':'KA','き':'KI','く':'KU','け':'KE','こ':'KO',
 'さ':'SA','し':'SI','す':'SU','せ':'SE','そ':'SO',
 'た':'TA','ち':'TI','つ':'TU','て':'TE','と':'TO',
 'な':'NA','に':'NI','ぬ':'NU','ね':'NE','の':'NO',
 'は':'HA','ひ':'HI','ふ':'FU','へ':'HE','ほ':'HO',
 'ま':'MA','み':'MI','む':'MU','め':'ME','も':'MO',
 'や':'YA','ゆ':'YU','よ':'YO',
 'ら':'RA','り':'RI','る':'RU','れ':'RE','ろ':'RO',
 'わ':'WA','ゐ':'WI','を':'WO','ゑ':'WE',
 'が':'GA','ぎ':'GI','ぐ':'GU','げ':'GE','ご':'GO',
 'ざ':'ZA','じ':'JI','ず':'ZU','ぜ':'ZE','ぞ':'ZO',
 'だ':'DA','ぢ':'DI','づ':'DU','で':'DE','ど':'DO',
 'ば':'BA','び':'BI','ぶ':'BU','べ':'BE','ぼ':'BO',
 'ぱ':'PA','ぴ':'PI','ぷ':'PU','ぺ':'PE','ぽ':'PO',
 'ぁ':'A','ぃ':'I','ぅ':'U','ぇ':'E','ぉ':'O','ゎ':'WA',
 'ゕ':'KA','ゖ':'KE',
}
_SMALL_Y = {'ゃ':'A','ゅ':'U','ょ':'O'}
_SPECIAL = {  # base-kana + small-ya  -> explicit forms that differ from the generic rule
 'し':'SH','ち':'CH','じ':'J','ぢ':'DY',
}
_EXTRA = {  # foreign-sound digraphs (small vowels)
 'ふぁ':'FA','ふぃ':'FI','ふぇ':'FE','ふぉ':'FO','ふゅ':'FYU',
 'てぃ':'TI','でぃ':'DI','とぅ':'TU','どぅ':'DU','てゅ':'TYU','でゅ':'DYU',
 'うぃ':'WI','うぇ':'WE','うぉ':'WO','ゔぁ':'VA','ゔぃ':'VI','ゔぇ':'VE','ゔぉ':'VO','ゔゅ':'VYU',
 'いぇ':'YE','しぇ':'SHE','じぇ':'JE','ちぇ':'CHE','つぁ':'TSA','つぃ':'TSI','つぇ':'TSE','つぉ':'TSO',
 'くぁ':'KWA','くぃ':'KWI','くぇ':'KWE','くぉ':'KWO','ぐぁ':'GWA',
}
_YOON_CONS = {'き':'KY','ぎ':'GY','に':'NY','ひ':'HY','び':'BY','ぴ':'PY','み':'MY','り':'RY'}


def to_romaji(reading: str):
    """Returns the upper-case romaji, or None when the reading has characters we cannot transcribe."""
    out = []
    i, n = 0, len(reading)
    sokuon = False
    while i < n:
        c = reading[i]
        nxt = reading[i + 1] if i + 1 < n else ''
        if c == 'っ':
            if i + 1 >= n:
                out.append('XTU')  # trailing small tsu, e.g. "あっ" -> AXTU
                i += 1
                continue
            sokuon = True
            i += 1
            continue
        if c == 'ん':
            piece = 'N'
            i += 1
        elif c == 'ー':
            # long vowel mark: repeat the previous vowel (A/I/U/E/O)
            if not out:
                return None
            piece = 'Ql'
            i += 1
        elif c + nxt in _EXTRA:
            piece = _EXTRA[c + nxt]
            i += 2
        elif nxt in _SMALL_Y and c in _YOON_CONS:
            piece = _YOON_CONS[c] + _SMALL_Y[nxt]
            i += 2
        elif nxt in _SMALL_Y and c in _SPECIAL:
            piece = _SPECIAL[c] + _SMALL_Y[nxt]
            i += 2
        elif c in _BASE:
            piece = _BASE[c]
            i += 1
        else:
            return None
        if sokuon:
            if piece[0] in 'AIUEON':
                return None
            piece = piece[0] + piece
            sokuon = False
        out.append(piece)
    return ''.join(out) if out else None
