package io.github.sspanak.tt9.ime.modes.helpers;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

import io.github.sspanak.tt9.util.TextTools;

/**
 * Customized build: knows the valid Chinese Pinyin syllables. It is used for the syllable separator
 * ("1" key), for example "bi'an" (币安) as opposed to "bian" (变).
 *
 * The dictionary stores a multi-character word with a plain transcription, without any separators:
 * "BIAN币安". To tell whether the word agrees with the separators the user typed, we check if the
 * transcription can be cut into exactly as many syllables as the word has characters, and if all
 * the cuts can be placed at the typed separators.
 */
public class PinyinSyllables {
	private static final int MAX_SYLLABLE_LENGTH = 6;

	private static final HashSet<String> SYLLABLES = new HashSet<>(Arrays.asList((
		"A AI AN ANG AO BA BAI BAN BANG BAO BEI BEN BENG BI BIAN BIANG BIAO BIE BIN BING BO BU CA CAI CAN " +
		"CANG CAO CE CEN CENG CHA CHAI CHAN CHANG CHAO CHE CHEN CHENG CHI CHONG CHOU CHU CHUA CHUAI CHUAN " +
		"CHUANG CHUI CHUN CHUO CI CONG COU CU CUAN CUI CUN CUO DA DAI DAN DANG DAO DE DEN DENG DI DIA DIAN " +
		"DIAO DIE DING DIU DONG DOU DU DUAN DUI DUN DUO E EI EN ENG ER FA FAN FANG FEI FEN FENG FIAO FO FOU " +
		"FU GA GAI GAN GANG GAO GE GEI GEN GENG GONG GOU GU GUA GUAI GUAN GUANG GUI GUN GUO HA HAI HAN HANG " +
		"HAO HE HEI HEN HENG HONG HOU HU HUA HUAI HUAN HUANG HUI HUN HUO JI JIA JIAN JIANG JIAO JIE JIN JING " +
		"JIONG JIU JU JUAN JUE JUN KA KAI KAN KANG KAO KE KEI KEN KENG KONG KOU KU KUA KUAI KUAN KUANG KUI " +
		"KUN KUO LA LAI LAN LANG LAO LE LEI LENG LI LIA LIAN LIANG LIAO LIE LIN LING LIU LONG LOU LU LUAN LUN " +
		"LUO LV LVE MA MAI MAN MANG MAO ME MEI MEN MENG MI MIAN MIAO MIE MIN MING MIU MO MOU MU NA NAI NAN " +
		"NANG NAO NE NEI NEN NENG NI NIAN NIANG NIAO NIE NIN NING NIU NONG NOU NU NUAN NUO NV NVE O OU PA PAI " +
		"PAN PANG PAO PEI PEN PENG PI PIAN PIAO PIE PIN PING PO POU PU QI QIA QIAN QIANG QIAO QIE QIN QING " +
		"QIONG QIU QU QUAN QUE QUN RAN RANG RAO RE REN RENG RI RONG ROU RU RUA RUAN RUI RUN RUO SA SAI SAN " +
		"SANG SAO SE SEN SENG SHA SHAI SHAN SHANG SHAO SHE SHEI SHEN SHENG SHI SHOU SHU SHUA SHUAI SHUAN " +
		"SHUANG SHUI SHUN SHUO SI SONG SOU SU SUAN SUI SUN SUO TA TAI TAN TANG TAO TE TENG TI TIAN TIAO TIE " +
		"TING TONG TOU TU TUAN TUI TUN TUO WA WAI WAN WANG WEI WEN WENG WO WU XI XIA XIAN XIANG XIAO XIE XIN " +
		"XING XIONG XIU XU XUAN XUE XUN YA YAN YANG YAO YE YI YIN YING YO YONG YOU YU YUAN YUE YUN ZA ZAI ZAN " +
		"ZANG ZAO ZE ZEI ZEN ZENG ZHA ZHAI ZHAN ZHANG ZHAO ZHE ZHEN ZHENG ZHI ZHONG ZHOU ZHU ZHUA ZHUAI ZHUAN " +
		"ZHUANG ZHUI ZHUN ZHUO ZI ZONG ZOU ZU ZUAN ZUI ZUN ZUO"
	).split(" ")));


	/**
	 * @param entry   a dictionary word with its transcription in front, for example: "BIAN币安"
	 * @param breaks  positions in the transcription, at which a syllable must end. For "bi'an" it is [2].
	 */
	public static boolean matches(@NonNull String entry, @NonNull List<Integer> breaks) {
		final int latinEnd = TextTools.lastIndexOfLatin(entry);
		if (latinEnd < 0 || latinEnd + 1 >= entry.length()) {
			return false;
		}

		final String transcription = entry.substring(0, latinEnd + 1).toUpperCase(Locale.ROOT);
		final String nativeWord = entry.substring(latinEnd + 1);
		final int characters = nativeWord.codePointCount(0, nativeWord.length());

		// the cut positions, including the start and the end
		final ArrayList<Integer> cuts = new ArrayList<>();
		cuts.add(0);
		for (int b : breaks) {
			if (b > cuts.get(cuts.size() - 1) && b < transcription.length()) {
				cuts.add(b);
			}
		}
		cuts.add(transcription.length());

		// the possible syllable counts, after processing each part between two cuts
		HashSet<Integer> totals = new HashSet<>();
		totals.add(0);

		for (int i = 1; i < cuts.size(); i++) {
			final HashSet<Integer> partCounts = getSyllableCounts(transcription.substring(cuts.get(i - 1), cuts.get(i)));
			if (partCounts.isEmpty()) {
				return false;
			}

			final HashSet<Integer> newTotals = new HashSet<>();
			for (int total : totals) {
				for (int count : partCounts) {
					if (total + count <= characters) {
						newTotals.add(total + count);
					}
				}
			}
			totals = newTotals;
		}

		return totals.contains(characters);
	}


	/**
	 * Customized build: the text for the Pinyin row. Puts an apostrophe at the separators typed by the user
	 * and cuts the remaining parts into syllables, when possible: "dajia" -> "da'jia", "bian" -> "bian".
	 */
	@NonNull
	public static String format(@NonNull String sound, @NonNull List<Integer> breaks) {
		final String upper = sound.toUpperCase(Locale.ROOT);
		final StringBuilder out = new StringBuilder();

		int start = 0;
		final ArrayList<Integer> cuts = new ArrayList<>(breaks);
		java.util.Collections.sort(cuts);
		cuts.add(upper.length());

		for (int cut : cuts) {
			if (cut <= start || cut > upper.length()) {
				continue;
			}

			if (out.length() > 0) {
				out.append('\'');
			}
			out.append(autoSplit(upper.substring(start, cut)));
			start = cut;
		}

		// a separator typed right after the sound: "bi'"
		if (out.length() > 0 && breaks.contains(upper.length())) {
			out.append('\'');
		}

		return out.length() == 0 ? sound : out.toString().toLowerCase(Locale.ROOT);
	}


	@NonNull
	private static String autoSplit(@NonNull String part) {
		final int n = part.length();
		final int none = Integer.MAX_VALUE;
		final int[] best = new int[n + 1];
		final int[] previous = new int[n + 1];
		java.util.Arrays.fill(best, none);
		best[0] = 0;

		for (int i = 1; i <= n; i++) {
			for (int len = 1; len <= Math.min(MAX_SYLLABLE_LENGTH, i); len++) {
				final int from = i - len;
				if (best[from] == none || !SYLLABLES.contains(part.substring(from, i))) {
					continue;
				}

				final int candidate = best[from] + 1;
				if (candidate < best[i] || (candidate == best[i] && from < previous[i])) {
					best[i] = candidate;
					previous[i] = from;
				}
			}
		}

		if (best[n] == none) {
			return part;
		}

		final ArrayList<String> pieces = new ArrayList<>();
		for (int i = n; i > 0; i = previous[i]) {
			pieces.add(0, part.substring(previous[i], i));
		}

		final StringBuilder joined = new StringBuilder();
		for (String piece : pieces) {
			if (joined.length() > 0) {
				joined.append('\'');
			}
			joined.append(piece);
		}

		return joined.toString();
	}


	/**
	 * All the possible numbers of syllables the given text can be split into. For example:
	 * "XIAN" -> [2] (XI + AN), "AN" -> [1, ...]. Returns an empty set when the text is not made of syllables.
	 */
	@NonNull
	private static HashSet<Integer> getSyllableCounts(@NonNull String text) {
		final int length = text.length();

		// counts[i] contains the syllable counts, with which we can reach position i
		@SuppressWarnings("unchecked")
		final HashSet<Integer>[] counts = new HashSet[length + 1];
		counts[0] = new HashSet<>();
		counts[0].add(0);

		for (int i = 0; i < length; i++) {
			if (counts[i] == null || counts[i].isEmpty()) {
				continue;
			}

			for (int end = i + 1; end <= Math.min(length, i + MAX_SYLLABLE_LENGTH); end++) {
				if (!SYLLABLES.contains(text.substring(i, end))) {
					continue;
				}

				if (counts[end] == null) {
					counts[end] = new HashSet<>();
				}

				for (int count : counts[i]) {
					counts[end].add(count + 1);
				}
			}
		}

		return counts[length] != null ? counts[length] : new HashSet<>();
	}
}
