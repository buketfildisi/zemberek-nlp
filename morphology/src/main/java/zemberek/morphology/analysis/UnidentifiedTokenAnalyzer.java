package zemberek.morphology.analysis;

import com.google.common.collect.Lists;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.antlr.v4.runtime.Token;
import zemberek.core.turkish.PrimaryPos;
import zemberek.core.turkish.RootAttribute;
import zemberek.core.turkish.SecondaryPos;
import zemberek.core.turkish.StemAndEnding;
import zemberek.core.turkish.Turkish;
import zemberek.core.turkish.TurkishAlphabet;
import zemberek.morphology.analysis.tr.PronunciationGuesser;
import zemberek.morphology.analysis.tr.TurkishNumbers;
import zemberek.morphology.analysis.tr.TurkishNumeralEndingMachine;
import zemberek.morphology.lexicon.DictionaryItem;
import zemberek.morphology.lexicon.RootLexicon;
import zemberek.tokenization.antlr.TurkishLexer;

//TODO: Code requires serious testing and review.
//TODO: For unknown pronouns, do not analyze as regular nouns if apostrophe is not in the
//        correct place. Such as [obama'ymış] should not have "oba" root solution.

public class UnidentifiedTokenAnalyzer {

  public static final TurkishAlphabet ALPHABET = TurkishAlphabet.INSTANCE;
  private static Map<String, String> ordinalMap = TurkishNumbers.getOrdinalMap();

  private RuleBasedAnalyzer analyzer;
  private RootLexicon lexicon;
  private TurkishAlphabet alphabet = TurkishAlphabet.INSTANCE;
  private TurkishNumeralEndingMachine numeralEndingMachine = new TurkishNumeralEndingMachine();

  public UnidentifiedTokenAnalyzer(RuleBasedAnalyzer analyzer) {
    this.analyzer = analyzer;
    this.lexicon = analyzer.getLexicon();
  }

  public static final Pattern nonLettersPattern =
      Pattern.compile("[^" + TurkishAlphabet.INSTANCE.getAllLetters() + "]");

  public synchronized List<SingleAnalysis> analyze(Token token) {
    SecondaryPos sPos = guessSecondaryPosType(token);
    String word = token.getText();

    // TODO: for now, for regular words and numbers etc, use the analyze method.
    if (sPos == SecondaryPos.None) {
      if (word.contains("?")) {
        return Collections.emptyList();
      }
      if (alphabet.containsDigit(word)) {
        return tryNumeral(token);
      } else {
        return analyzeWord(word);
      }
    }

    if (sPos == SecondaryPos.RomanNumeral) {
      return getForRomanNumeral(token);
    }
    if (sPos == SecondaryPos.Date || sPos == SecondaryPos.Time) {
      return tryNumeral(token);
    }

    //TODO: consider returning analysis results without interfering with analyzer.
    String normalized = nonLettersPattern.matcher(word).replaceAll("");
    DictionaryItem item = new DictionaryItem(word, word, normalized, PrimaryPos.Noun, sPos);

    boolean itemDoesNotExist = !lexicon.containsItem(item);
    if (itemDoesNotExist) {
      item.attributes.add(RootAttribute.Runtime);
      analyzer.getStemTransitions().addDictionaryItem(item);
    }
    List<SingleAnalysis> results = analyzer.analyze(word);
    if (itemDoesNotExist) {
      analyzer.getStemTransitions().removeDictionaryItem(item);
    }
    return results;
  }

  private SecondaryPos guessSecondaryPosType(Token token) {
    switch (token.getType()) {
      case TurkishLexer.Email:
        return SecondaryPos.Email;
      case TurkishLexer.URL:
        return SecondaryPos.Url;
      case TurkishLexer.HashTag:
        return SecondaryPos.HashTag;
      case TurkishLexer.Mention:
        return SecondaryPos.Mention;
      case TurkishLexer.Emoticon:
        return SecondaryPos.Emoticon;
      case TurkishLexer.RomanNumeral:
        return SecondaryPos.RomanNumeral;
      case TurkishLexer.AbbreviationWithDots:
        return SecondaryPos.Abbreviation;
      case TurkishLexer.Date:
        return SecondaryPos.Date;
      case TurkishLexer.Time: // TODO: consider SecondaryPos.Time -> Temporal and Clock -> Time
        return SecondaryPos.Clock;

      default:
        return SecondaryPos.None;
    }
  }

  public synchronized List<SingleAnalysis> analyzeWord(String word) {
    int index = word.indexOf('\'');
    if (index >= 0) {
      return tryWordWithApostrophe(word);
    } else if (Character.isUpperCase(word.charAt(0))) {
      return tryWithoutApostrophe(word);
    }
    return Collections.emptyList();
  }

  private List<SingleAnalysis> tryWithoutApostrophe(String word) {
    String normalized = null;
    TurkishAlphabet alphabet = TurkishAlphabet.INSTANCE;
    if (alphabet.containsAsciiForeignDiacritics(word)) {
      normalized = alphabet.foreignDiacriticsToTurkish(word);
    }
    normalized = normalized == null ?
        alphabet.normalize(word) :
        alphabet.normalize(normalized);

    //TODO: should we remove dots with normalization?
    String pronunciation = guessPronunciation(normalized.replaceAll("[.]", ""));
    DictionaryItem itemProp = new DictionaryItem(
        Turkish.capitalize(normalized),
        normalized,
        pronunciation,
        PrimaryPos.Noun,
        word.contains(".") ? SecondaryPos.Abbreviation : SecondaryPos.ProperNoun);

    boolean itemDoesNotExist = !lexicon.containsItem(itemProp);

    if (!itemDoesNotExist) {
      itemProp.attributes.add(RootAttribute.Runtime);
      analyzer.getStemTransitions().addDictionaryItem(itemProp);
    }
    List<SingleAnalysis> properResults = analyzer.analyze(normalized);
    if (itemDoesNotExist) {
      analyzer.getStemTransitions().removeDictionaryItem(itemProp);
    }
    return properResults;
  }

  private List<SingleAnalysis> tryWordWithApostrophe(String word) {
    String normalized = TurkishAlphabet.INSTANCE.normalizeApostrophe(word);

    int index = normalized.indexOf('\'');
    if (index <= 0 || index == normalized.length() - 1) {
      return Collections.emptyList();
    }
    String stem = normalized.substring(0, index);
    String ending = normalized.substring(index + 1);

    StemAndEnding se = new StemAndEnding(stem, ending);
    //TODO: should we remove dots with normalization?
    String stemNormalized = TurkishAlphabet.INSTANCE.normalize(se.stem).replaceAll("[.]", "");
    String endingNormalized = TurkishAlphabet.INSTANCE.normalize(se.ending);
    String pronunciation = guessPronunciation(stemNormalized);
    DictionaryItem itemProp = new DictionaryItem(
        Turkish.capitalize(stemNormalized),
        stemNormalized,
        pronunciation,
        PrimaryPos.Noun,
        SecondaryPos.ProperNoun);
    boolean itemDoesNotExist = !lexicon.containsItem(itemProp);
    if (itemDoesNotExist) {
      itemProp.attributes.add(RootAttribute.Runtime);
      analyzer.getStemTransitions().addDictionaryItem(itemProp);
    }
    String toParse = stemNormalized + endingNormalized;

    List<SingleAnalysis> noQuotesParses = analyzer.analyze(toParse);
    if (itemDoesNotExist) {
      analyzer.getStemTransitions().removeDictionaryItem(itemProp);
    }
    return noQuotesParses.stream()
        .filter(noQuotesParse -> noQuotesParse.getStem().equals(stemNormalized))
        .collect(Collectors.toList());
  }

  PronunciationGuesser guesser = new PronunciationGuesser();

  private String guessPronunciation(String stem) {
    if (!Turkish.Alphabet.containsVowel(stem)) {
      return guesser.toTurkishLetterPronunciations(stem);
    } else {
      return stem;
    }
  }

  private StemAndEnding getFromNumeral(String s) {
    if (s.contains("'")) {
      int i = s.indexOf('\'');
      return new StemAndEnding(s.substring(0, i), s.substring(i + 1));
    }
    int j = 0;
    for (int i = s.length() - 1; i >= 0; i--) {
      char c = s.charAt(i);
      int k = c - '0';
      if (c == '.') { // ordinal
        break;
      }
      if (k < 0 || k > 9) {
        j++;
      } else {
        break;
      }
    }
    int cutPoint = s.length() - j;
    return new StemAndEnding(s.substring(0, cutPoint), s.substring(cutPoint));
  }

  private List<SingleAnalysis> getForRomanNumeral(Token token) {
    String content = token.getText();
    StemAndEnding se;
    if (content.contains("'")) {
      int i = content.indexOf('\'');
      se = new StemAndEnding(content.substring(0, i), content.substring(i + 1));
    } else {
      se = new StemAndEnding(content, "");
    }
    String ss = se.stem;
    if (se.stem.endsWith(".")) {
      ss = se.stem.substring(0, se.stem.length() - 1);
    }
    int decimal = TurkishNumbers.romanToDecimal(ss);
    if (decimal == -1) {
      return new ArrayList<>(0);
    }

    String lemma;
    if (se.stem.endsWith(".")) {
      lemma = numeralEndingMachine.find(String.valueOf(decimal));
      lemma = ordinalMap.get(lemma);
    } else {
      lemma = numeralEndingMachine.find(String.valueOf(decimal));
    }
    List<SingleAnalysis> results = Lists.newArrayListWithCapacity(1);
    String toParse;
    if (se.ending.length() > 0 && lemma.equals("dört") &&
        ALPHABET.isVowel(se.ending.charAt(0))) {
      toParse = "dörd" + se.ending;
    } else {
      toParse = lemma + se.ending;
    }
    List<SingleAnalysis> res = analyzer.analyze(toParse);
    for (SingleAnalysis re : res) {
      if (re.getDictionaryItem().primaryPos != PrimaryPos.Numeral) {
        continue;
      }
      DictionaryItem runTimeItem = new DictionaryItem(
          se.stem,
          se.stem,
          content + lemma,
          PrimaryPos.Numeral,
          SecondaryPos.RomanNumeral);
      runTimeItem.attributes.add(RootAttribute.Runtime);
      results.add(re.copyFor(runTimeItem, se.stem));
    }
    return results;
  }

  private List<SingleAnalysis> tryNumeral(Token token) {
    String s = token.getText();
    s = s.toLowerCase(TurkishAlphabet.TR);
    StemAndEnding se = getFromNumeral(s);
    String lemma;
    if (se.stem.endsWith(".")) {
      String ss = se.stem.substring(0, se.stem.length() - 1);
      lemma = numeralEndingMachine.find(ss);
      lemma = ordinalMap.get(lemma);
    } else {
      lemma = numeralEndingMachine.find(se.stem);
    }

    List<SingleAnalysis> results = Lists.newArrayListWithCapacity(1);

    for (Numerals numerals : Numerals.values()) {
      Matcher m = numerals.pattern.matcher(se.stem);
      if (m.find()) {
        String toParse;
        if (se.ending.length() > 0 && lemma.equals("dört") &&
            ALPHABET.isVowel(se.ending.charAt(0))) {
          toParse = "dörd" + se.ending;
        } else {
          toParse = lemma + se.ending;
        }
        List<SingleAnalysis> res = analyzer.analyze(toParse);
        for (SingleAnalysis re : res) {
          if (re.getDictionaryItem().primaryPos != PrimaryPos.Numeral) {
            continue;
          }
          DictionaryItem runTimeItem = new DictionaryItem(
              se.stem,
              se.stem,
              s + lemma,
              PrimaryPos.Numeral,
              numerals.secondaryPos);
          runTimeItem.attributes.add(RootAttribute.Runtime);
          results.add(re.copyFor(runTimeItem, se.stem));
        }
      }
    }
    return results;
  }

  // TODO: move this functionality to Lexer.
  public enum Numerals {
    CARDINAL("#", "^[+\\-]?\\d+$", SecondaryPos.Cardinal),
    ORDINAL("#.", "^[+\\-]?[0-9]+[.]$", SecondaryPos.Ordinal),
    RANGE("#-#", "^[+\\-]?[0-9]+-[0-9]+$", SecondaryPos.Range),
    RATIO("#/#", "^[+\\-]?[0-9]+/[0-9]+$", SecondaryPos.Ratio),
    REAL("#,#", "^[+\\-]?[0-9]+[,][0-9]+$|^[+\\-]?[0-9]+[.][0-9]+$", SecondaryPos.Real),
    DISTRIB("#DIS", "^\\d+[^0-9]+$", SecondaryPos.Distribution),
    PERCENTAGE_BEFORE("%#", "(^|[+\\-])(%)(\\d+)((([.]|[,])(\\d+))|)$", SecondaryPos.Percentage),
    TIME("#:#", "^([012][0-9]|[1-9])([.]|[:])([0-5][0-9])$", SecondaryPos.Time),
    DATE("##.##.####", "^([0-3][0-9]|[1-9])([.]|[/])([01][0-9]|[1-9])([.]|[/])(\\d{4})$",
        SecondaryPos.Date);

    public String lemma;
    public Pattern pattern;
    public SecondaryPos secondaryPos;

    Numerals(String lemma, String patternStr, SecondaryPos secondaryPos) {
      this.lemma = lemma;
      this.pattern = Pattern.compile(patternStr);
      this.secondaryPos = secondaryPos;
    }
  }

}
