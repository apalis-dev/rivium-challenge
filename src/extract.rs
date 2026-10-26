use scraper::{ElementRef, Html, Selector};
use url::Url;

const WORDS_PER_MINUTE: usize = 230;
const TRACKING_PARAMS: &[&str] = &["fbclid", "gclid", "igshid", "mc_cid", "mc_eid", "si", "ref_src"];

/// Pulls the first http(s) link out of whatever another app shared. Apps rarely
/// share a bare URL: YouTube, X and news apps send "Title https://... via @app".
pub fn find_link(shared: &str) -> Option<Url> {
    shared.split_whitespace().find_map(|token| {
        let start = token.find("http://").or_else(|| token.find("https://"))?;
        let url = Url::parse(trim_trailing_punctuation(&token[start..])).ok()?;
        url.host_str().is_some().then(|| clean(url))
    })
}

// A closing bracket is only punctuation when unbalanced: "(see https://x.com/a)"
// vs. https://en.wikipedia.org/wiki/Rust_(programming_language).
fn trim_trailing_punctuation(mut s: &str) -> &str {
    loop {
        let Some(last) = s.chars().last() else { return s };
        let strip = match last {
            '.' | ',' | ';' | ':' | '!' | '?' | '"' | '\'' | '>' => true,
            ')' => s.matches('(').count() < s.matches(')').count(),
            ']' => s.matches('[').count() < s.matches(']').count(),
            _ => false,
        };
        if !strip {
            return s;
        }
        s = &s[..s.len() - last.len_utf8()];
    }
}

/// Normalises a link so the same article shared twice is stored once.
pub fn clean(mut url: Url) -> Url {
    url.set_fragment(None);
    let kept: Vec<(String, String)> = url
        .query_pairs()
        .filter(|(k, _)| !k.starts_with("utm_") && !TRACKING_PARAMS.contains(&k.as_ref()))
        .map(|(k, v)| (k.into_owned(), v.into_owned()))
        .collect();
    if kept.is_empty() {
        url.set_query(None);
    } else {
        url.query_pairs_mut().clear().extend_pairs(kept);
    }
    url
}

pub fn site_name(url: &Url) -> String {
    url.host_str().unwrap_or_default().trim_start_matches("www.").to_string()
}

#[derive(Debug, Default, PartialEq)]
pub struct Article {
    /// "article", "video" or "link" (a page with no readable text, or a non-HTML file).
    pub kind: &'static str,
    pub title: String,
    pub site: String,
    pub author: String,
    pub description: String,
    pub body: String,
    pub word_count: usize,
    pub reading_minutes: usize,
    pub duration_seconds: u64,
}

pub fn reading_minutes(words: usize) -> usize {
    words.div_ceil(WORDS_PER_MINUTE)
}

impl Article {
    /// For links that aren't web pages (PDFs, images): keep the link, skip the reader view.
    pub fn link_only(url: &Url, kind: &str) -> Self {
        let name = url
            .path_segments()
            .and_then(|mut s| s.next_back())
            .filter(|s| !s.is_empty())
            .unwrap_or("Untitled link");
        Article {
            kind: "link",
            title: name.to_string(),
            site: site_name(url),
            description: format!("Saved as a link ({kind})"),
            ..Default::default()
        }
    }
}

pub fn article_from_html(html: &str, url: &Url) -> Article {
    let doc = Html::parse_document(html);

    let title = meta(&doc, "property", "og:title")
        .or_else(|| meta(&doc, "name", "twitter:title"))
        .or_else(|| first_text(&doc, "title"))
        .or_else(|| first_text(&doc, "h1"))
        .unwrap_or_else(|| url.to_string());
    let site = meta(&doc, "property", "og:site_name").unwrap_or_else(|| site_name(url));
    let description = meta(&doc, "property", "og:description")
        .or_else(|| meta(&doc, "name", "description"))
        .or_else(|| meta(&doc, "itemprop", "description"))
        .unwrap_or_default();
    let author = attr_of(&doc, "[itemprop=author] [itemprop=name]", "content")
        .or_else(|| meta(&doc, "name", "author"))
        .unwrap_or_default();

    let is_video = meta(&doc, "property", "og:type").is_some_and(|t| t.starts_with("video"))
        || meta(&doc, "name", "twitter:card").as_deref() == Some("player");

    let (kind, body, duration_seconds) = if is_video {
        let duration = meta(&doc, "itemprop", "duration")
            .or_else(|| meta(&doc, "property", "video:duration"))
            .and_then(|d| parse_duration(&d))
            .unwrap_or(0);
        ("video", String::new(), duration)
    } else {
        let body = readable_text(&doc);
        (if body.is_empty() { "link" } else { "article" }, body, 0)
    };
    let word_count = body.split_whitespace().count();

    Article {
        kind,
        title: squash(&title),
        site: squash(&site),
        author: squash(&author),
        description: squash(&description),
        body,
        word_count,
        reading_minutes: reading_minutes(word_count),
        duration_seconds,
    }
}

/// ISO 8601 durations as used by schema.org ("PT1H2M30S") or plain seconds ("150").
fn parse_duration(s: &str) -> Option<u64> {
    if let Ok(secs) = s.parse() {
        return Some(secs);
    }
    let rest = s.strip_prefix("PT")?;
    let (mut total, mut num) = (0u64, String::new());
    for c in rest.chars() {
        match c {
            '0'..='9' => num.push(c),
            'H' | 'M' | 'S' => {
                let n: u64 = num.parse().ok()?;
                total += n * match c { 'H' => 3600, 'M' => 60, _ => 1 };
                num.clear();
            }
            _ => return None,
        }
    }
    Some(total)
}

fn attr_of(doc: &Html, css: &str, attr: &str) -> Option<String> {
    let selector = Selector::parse(css).ok()?;
    doc.select(&selector)
        .find_map(|el| el.value().attr(attr))
        .map(squash)
        .filter(|s| !s.is_empty())
}

fn meta(doc: &Html, attr: &str, key: &str) -> Option<String> {
    let selector = Selector::parse(&format!(r#"meta[{attr}="{key}"]"#)).ok()?;
    doc.select(&selector)
        .find_map(|el| el.value().attr("content"))
        .map(squash)
        .filter(|s| !s.is_empty())
}

fn first_text(doc: &Html, css: &str) -> Option<String> {
    let selector = Selector::parse(css).ok()?;
    doc.select(&selector)
        .map(|el| squash(&el.text().collect::<String>()))
        .find(|s| !s.is_empty())
}

/// A small reader-mode: take the article/main container (or body), keep paragraphs
/// and headings, and drop navigation, footers and very short boilerplate lines.
fn readable_text(doc: &Html) -> String {
    let container = ["article", "main", "[role=main]", "body"]
        .iter()
        .filter_map(|css| Selector::parse(css).ok())
        .find_map(|sel| doc.select(&sel).next());
    let Some(container) = container else { return String::new() };

    let blocks = Selector::parse("p, h2, h3, li, blockquote, pre").expect("static selector");
    let mut out: Vec<String> = Vec::new();
    for el in container.select(&blocks) {
        if inside_chrome(el) {
            continue;
        }
        let name = el.value().name();
        let text = squash(&el.text().collect::<String>());
        let keep = match name {
            "h2" | "h3" => !text.is_empty(),
            _ => text.len() >= 40 && !has_block_ancestor(el),
        };
        if keep && out.last() != Some(&text) {
            out.push(text);
        }
    }
    out.join("\n\n")
}

fn inside_chrome(el: ElementRef) -> bool {
    el.ancestors().filter_map(ElementRef::wrap).any(|a| {
        let v = a.value();
        matches!(v.name(), "nav" | "footer" | "header" | "aside" | "form" | "script" | "style" | "noscript")
            || v.attr("role") == Some("navigation")
            || v.id().is_some_and(is_toc)
            || v.classes().any(is_toc)
    })
}

// Tables of contents ("toc", "mw-toc", "table-of-contents") read as noise in a reader view.
fn is_toc(name: &str) -> bool {
    let name = name.to_ascii_lowercase();
    name == "toc" || name.ends_with("-toc") || name.starts_with("toc-") || name.contains("table-of-contents")
}

// Avoids emitting a <p> twice when it sits inside a kept <li> or <blockquote>.
fn has_block_ancestor(el: ElementRef) -> bool {
    el.ancestors()
        .filter_map(ElementRef::wrap)
        .any(|a| matches!(a.value().name(), "li" | "blockquote" | "pre"))
}

fn squash(s: &str) -> String {
    s.split_whitespace().collect::<Vec<_>>().join(" ")
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn finds_link_inside_shared_text() {
        let url = find_link("Check this out: https://example.com/post?utm_source=x&id=7#top via @someone").unwrap();
        assert_eq!(url.as_str(), "https://example.com/post?id=7");
    }

    #[test]
    fn strips_trailing_punctuation_and_tracking() {
        let url = find_link("(see https://youtu.be/abc123?si=TRACK).").unwrap();
        assert_eq!(url.as_str(), "https://youtu.be/abc123");
    }

    #[test]
    fn keeps_balanced_parentheses() {
        let url = find_link("Read https://en.wikipedia.org/wiki/Rust_(programming_language).").unwrap();
        assert_eq!(url.as_str(), "https://en.wikipedia.org/wiki/Rust_(programming_language)");
    }

    #[test]
    fn no_link_in_plain_text() {
        assert!(find_link("just some words, no link here").is_none());
    }

    #[test]
    fn extracts_article_and_skips_chrome() {
        let html = r#"<html><head>
            <title>Fallback</title>
            <meta property="og:title" content="How Queues Work">
            <meta property="og:site_name" content="Example Blog">
            <meta name="description" content="A short intro.">
            </head><body>
            <nav><p>Home About Contact and a long list of navigation items here</p></nav>
            <article>
              <h2>Background</h2>
              <p>Background jobs let an app defer slow work and retry it when the network is flaky.</p>
              <p>Short.</p>
              <p>Durable storage means a job survives the process being killed by the operating system.</p>
              <div id="toc"><ul><li>1 Background and a long table of contents entry here</li></ul></div>
            </article>
            <footer><p>Copyright notice that is definitely longer than forty characters</p></footer>
            </body></html>"#;
        let url = Url::parse("https://www.example.com/q").unwrap();
        let a = article_from_html(html, &url);
        assert_eq!(a.title, "How Queues Work");
        assert_eq!(a.site, "Example Blog");
        assert_eq!(a.description, "A short intro.");
        assert!(a.body.starts_with("Background\n\nBackground jobs"));
        assert!(!a.body.contains("Copyright"));
        assert!(!a.body.contains("table of contents"));
        assert!(!a.body.contains("Short."));
        assert_eq!(a.reading_minutes, 1);
        assert_eq!(a.kind, "article");
    }

    #[test]
    fn detects_video_with_duration_and_channel() {
        let html = r#"<html><head>
            <meta property="og:type" content="video.other">
            <meta property="og:site_name" content="YouTube">
            <meta property="og:title" content="How This Robot Melts And Reforms">
            <meta property="og:description" content="">
            </head><body>
            <div itemscope><meta itemprop="duration" content="PT1M32S">
              <span itemprop="author"><link itemprop="name" content="Zack D. Films"></span></div>
            <p>Some unrelated page boilerplate text that is long enough to count.</p>
            </body></html>"#;
        let a = article_from_html(html, &Url::parse("https://youtube.com/shorts/x").unwrap());
        assert_eq!(a.kind, "video");
        assert_eq!(a.author, "Zack D. Films");
        assert_eq!(a.duration_seconds, 92);
        assert_eq!(a.body, "");
        assert_eq!(a.reading_minutes, 0);
    }

    #[test]
    fn page_without_text_is_a_link_not_a_one_minute_read() {
        let html = r#"<html><head><title>App shell</title></head><body><div id="root"></div></body></html>"#;
        let a = article_from_html(html, &Url::parse("https://example.com/").unwrap());
        assert_eq!(a.kind, "link");
        assert_eq!(a.reading_minutes, 0);
    }

    #[test]
    fn parses_iso_durations() {
        assert_eq!(parse_duration("PT0M32S"), Some(32));
        assert_eq!(parse_duration("PT1H2M3S"), Some(3723));
        assert_eq!(parse_duration("150"), Some(150));
        assert_eq!(parse_duration("soon"), None);
    }

    #[test]
    fn link_only_uses_file_name() {
        let url = Url::parse("https://www.example.org/papers/report.pdf").unwrap();
        let a = Article::link_only(&url, "application/pdf");
        assert_eq!(a.title, "report.pdf");
        assert_eq!(a.site, "example.org");
    }
}
