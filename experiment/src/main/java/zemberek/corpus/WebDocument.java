package zemberek.corpus;

import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import zemberek.core.text.Regexps;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

public class WebDocument {
    String source;
    String id;
    String url;
    String crawlDate;
    String labelString;
    String category;

    List<String> lines = new ArrayList<>();

    public WebDocument(String source, String id, List<String> lines, String url,
                       String crawlDate, String labels, String category) {
        this.source = source;
        this.id = id;
        this.lines = lines;
        this.url = url;
        this.crawlDate = crawlDate;
        this.labelString = labels;
        this.category = category;
    }

    public String getDocumentHeader() {
        return "<doc id=\"" + id + "\" source=\"" + source + "\" crawl-date=\"" + crawlDate +
                "\" labels=\"" + labelString + "\" category=\"" + category + "\">";
    }

    public WebDocument emptyContent() {
        return new WebDocument(
                this.source,
                this.id,
                Collections.emptyList(),
                this.url,
                "", "", "");
    }

    public String getLabelString() {
        return labelString;
    }

    public String getCategory() {
        return category;
    }

    public List<String> getLabels() {
        return Splitter.on(",").omitEmptyStrings().trimResults().splitToList(labelString);
    }

    static Pattern sourcePattern = Pattern.compile("(source=\")(.+?)(\")");
    static Pattern urlPattern = Pattern.compile("(id=\")(.+?)(\")");
    static Pattern crawlDatePattern = Pattern.compile("(crawl-date=\")(.+?)(\")");
    static Pattern labelPattern = Pattern.compile("(labels=)(.+?)(\")");
    static Pattern categoryPattern = Pattern.compile("(category=)(.+?)(\")");

    public static WebDocument fromText(String meta, List<String> pageData) {

        String url = Regexps.firstMatch(urlPattern, meta, 2);
        String id = url.replaceAll("http://|https://", "");
        String source = Regexps.firstMatch(sourcePattern, meta, 2);
        String crawlDate = Regexps.firstMatch(crawlDatePattern, meta, 2);
        String labels = Regexps.firstMatch(labelPattern, meta, 2).replace('\"', ' ').trim();
        String category = Regexps.firstMatch(categoryPattern, meta, 2).replace('\"', ' ').trim();

        int i = source.lastIndexOf("/");
        if (i >= 0 && i < source.length()) {
            source = source.substring(i + 1);
        }
        return new WebDocument(source, id, pageData, url, crawlDate, labels, category);
    }

    public long contentHash() {
        return com.google.common.hash.Hashing.murmur3_128().hashUnencodedChars(content()).asLong();
    }

    public String content() {
        return Joiner.on("\n").join(lines);
    }

    public WebDocument copy(Collection<String> reduced) {
        return new WebDocument(this.source, this.id, new ArrayList<>(reduced), this.url, this.crawlDate, this.labelString, this.category);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        WebDocument page = (WebDocument) o;

        return page.contentHash() == this.contentHash();

    }

    @Override
    public int hashCode() {
        long h = contentHash();
        return (int) ((h & 0xffffffffL) ^ (h >> 32));
    }

}
