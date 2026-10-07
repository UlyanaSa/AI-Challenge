// Конвертер страниц книги из docs/book.pdf в корпус дневного задания: PDF → Markdown.
//
// Зачем инструмент лежит в репозитории, хотя запускается один раз: без него происхождение
// файлов корпуса не восстановить, а расхождение «в книге так, в корпусе иначе» было бы нечем
// проверить. Ни PDF, ни собранные из него главы в репозиторий не попадают (авторское право,
// `.gitignore`): инструмент восстанавливает их там, где исходник есть, — из него же страница
// собирает встроенный корпус дня, но уже своим разбором (indexing-ui/.../PdfSource.kt).
//
// Запуск (macOS, PDFKit из системного SDK):
//   swiftc -O indexing/tools/pdf-to-corpus.swift -o /tmp/pdf-to-corpus
//   /tmp/pdf-to-corpus book.pdf indexing/src/test/resources/corpus
//
// Что делает: страницы 105–142 («Часть IV. Принципы организации компонентов») режет на четыре
// файла по главам и восстанавливает Markdown-структуру по кеглю вёрстки:
//   16 pt полужирный — заголовок раздела (##), 14 pt полужирный — подраздел (###),
//   9.5 pt — эпиграф принципа, 9 pt полужирный «Рис. …» — подпись рисунка,
//   9 pt короткие строки — листинг кода, 10–11 pt — основной текст.
// Колонтитулы (номер страницы, «NN Глава …»), номера частей и глав огромным кеглем, подписи на
// рисунках (8 pt, 17.3 pt, 20 pt, надстрочные сноски 5.2 pt) отбрасываются: это элементы вёрстки,
// а не текст книги, и в отрыве от картинки они не значат ничего.
//
// Внутри абзаца строки склеиваются, перенос слова по слогам («архитек-туры») снимается,
// граница абзаца ставится там, где строка кончается знаком конца предложения, а следующая
// начинается с заглавной буквы, цифры или кавычки.

import Foundation
import PDFKit
import AppKit

struct Line {
    let text: String
    let size: Double
    let bold: Bool
}

/// Один выходной файл корпуса: глава книги и её страницы.
struct Chapter {
    let file: String
    let title: String
    let from: Int
    let to: Int
}

let chapters = [
    Chapter(file: "part-iv-intro.md", title: "Часть IV. Принципы организации компонентов", from: 105, to: 105),
    Chapter(file: "chapter-12-components.md", title: "Компоненты", from: 106, to: 112),
    Chapter(file: "chapter-13-component-cohesion.md", title: "Связность компонентов", from: 113, to: 120),
    Chapter(file: "chapter-14-component-coupling.md", title: "Сочетаемость компонентов", from: 121, to: 142),
]

let pdfPath = CommandLine.arguments[1]
let outDir = CommandLine.arguments[2]
guard let document = PDFDocument(url: URL(fileURLWithPath: pdfPath)) else {
    FileHandle.standardError.write("Не удалось открыть \(pdfPath)\n".data(using: .utf8)!)
    exit(1)
}

/// Строки страницы с размером и насыщенностью шрифта — по ним и восстанавливается структура.
func pageLines(_ page: PDFPage) -> [Line] {
    guard let attributed = page.attributedString else { return [] }
    var result: [Line] = []
    let text = attributed.string as NSString
    var lineStart = 0
    for index in 0..<text.length {
        let last = index == text.length - 1
        if text.character(at: index) == 10 || last {
            let range = NSRange(location: lineStart, length: max(0, index - lineStart))
            let trimmed = text.substring(with: range).trimmingCharacters(in: .whitespaces)
            if !trimmed.isEmpty {
                var size = 0.0
                var bold = false
                if range.length > 0,
                   let font = attributed.attribute(.font, at: range.location, effectiveRange: nil) as? NSFont {
                    size = font.pointSize
                    bold = font.fontDescriptor.symbolicTraits.contains(.bold)
                        || font.fontName.lowercased().contains("bold")
                }
                result.append(Line(text: trimmed, size: size, bold: bold))
            }
            lineStart = index + 1
        }
    }
    return result
}

/// Колонтитул: «108 Глава 12. Компоненты» сверху, «Краткая история компонентов 107» сверху,
/// одинокий номер страницы. Номер в колонтитуле совпадает с номером страницы PDF.
func isPageFurniture(_ line: Line, page: Int) -> Bool {
    let text = line.text
    if text == "\(page)" { return true }
    if text.hasSuffix(" \(page)"), !text.contains(". ") { return true }
    if line.bold, line.size >= 9.9, line.size <= 10.2,
       text.range(of: "^\\d{1,3}\\s+\\S", options: .regularExpression) != nil {
        return true
    }
    return false
}

/// Основной текст страницы: заголовок, эпиграф принципа, абзац или подпись рисунка. Всё, что
/// идёт в вёрстке после последней такой строки, — нижняя часть страницы (сноски, колонтитул).
func isBodyLine(_ line: Line, page: Int) -> Bool {
    if isPageFurniture(line, page: page) { return false }
    let size = line.size
    if size >= 13.5 { return true }
    if size >= 9.4 && size <= 9.6 { return true }
    if size >= 10.05 && size <= 11.6 && !line.bold { return true }
    return false
}

func endsSentence(_ text: String) -> Bool {
    guard let last = text.last else { return false }
    if last == "." || last == "!" || last == "?" || last == "»" || last == "\"" { return true }
    if last == ")" || last == "”" {
        guard text.count >= 2 else { return false }
        return ".!?»".contains(text[text.index(text.endIndex, offsetBy: -2)])
    }
    return false
}

func startsSentence(_ text: String) -> Bool {
    guard let first = text.first else { return false }
    return first.isUppercase || first == "«" || first == "\"" || first.isNumber
}

extension Character {
    /// Буква кириллицы — по ней отличаем перенос слова от дефиса внутри сложного слова.
    var isCyrillic: Bool {
        unicodeScalars.allSatisfy { (0x0400...0x04FF).contains($0.value) }
    }
}

/// Склеивает слово, разорванное переносом вёрстки, или возвращает nil, если это не перенос.
///
/// «архитек-туры» и «компи-ляцию» — перенос: дефис снимается, слово склеивается. «jar-файлы» и
/// «Fan-Out» — сложные слова: дефис стоит в тексте, и его надо сохранить. Признак — латиница
/// или цифры перед дефисом: в этой книге переносы стоят только внутри кириллических слов.
func mergeBrokenWord(_ previous: String, _ next: String) -> String? {
    guard previous.hasSuffix("-"), !previous.hasSuffix("--"), let first = next.first, first.isLetter else { return nil }
    let stem = String(previous.dropLast())
    let fragment = String(stem.reversed().prefix { $0.isLetter || $0.isNumber }.reversed())
    guard !fragment.isEmpty else { return nil }
    if fragment.contains(where: { !$0.isCyrillic }) { return stem + "-" + next }
    return first.isLowercase ? stem + next : nil
}

func clean(_ text: String) -> String {
    // «рис . 12.1» — пробел перед точкой оставила вёрстка. Но пробел снимается только когда за
    // знаком не идёт слово: иначе «файл .war» и «.Net» превратились бы в «файл.war» и «В.Net».
    let result = text
        .replacingOccurrences(of: "\\s+([.,;:!?])(?=\\s|$)", with: "$1", options: .regularExpression)
        .replacingOccurrences(of: "\\s+", with: " ", options: .regularExpression)
    return result.trimmingCharacters(in: .whitespaces)
}

final class Builder {
    private var title: String
    private var blocks: [String] = []
    private var paragraph: String?
    private var pull: String?
    private var caption: String?
    private var heading: String?
    private var headingLevel = 2
    private var small: [(text: String, page: Int)] = []
    private var footnote: String?
    private var footnotes: [String] = []
    private var lastMarkedPage = 0

    init(title: String) { self.title = title }

    /// Отмечает начало блока страницей, на которой он начинается.
    ///
    /// Маркер ставится только на границе блоков и только у первого блока страницы: текст абзаца
    /// через страницы не рвётся, поэтому разметка страниц не меняет ни одного абзаца. Читатель
    /// корпуса (`Corpus`) снимает маркеры и запоминает смещения — по ним чанк и узнаёт свои
    /// страницы, а в сам текст чанка маркеры не попадают.
    private func markBlock(_ page: Int) {
        if page != lastMarkedPage {
            blocks.append("<!-- page:\(page) -->")
            lastMarkedPage = page
        }
    }

    /// Абзац и всё, что к нему относится: сноска внизу страницы печатается после абзаца, который
    /// в вёрстке разорван номером сноски, — иначе текст сноски вклинился бы в середину фразы.
    private func flushParagraph() {
        if let text = paragraph { blocks.append(clean(text)) }
        paragraph = nil
        if !footnotes.isEmpty {
            blocks.append(contentsOf: footnotes)
            footnotes = []
        }
    }

    /// Эпиграф принципа: строки 9.5 pt склеиваются в один абзац и закрываются, как только
    /// начинается что-то другое, — иначе продолжение принципа уехало бы в текст главы.
    private func flushPull() {
        if let text = pull { blocks.append(clean(text)) }
        pull = nil
    }

    /// Подпись рисунка: «Рис. 14.10. …» бывает перенесена на вторую строку, и её хвост идёт
    /// обычным кеглем — без склейки он превратился бы в абзац про картинку.
    private func flushCaption() {
        if let text = caption { blocks.append(clean(text)) }
        caption = nil
    }

    private func appendPull(_ text: String) {
        guard let current = pull else {
            pull = text
            return
        }
        pull = mergeBrokenWord(current, text) ?? (current + " " + text)
    }

    private func flushHeading() {
        if let text = heading {
            blocks.append(String(repeating: "#", count: headingLevel) + " " + clean(text))
        }
        heading = nil
    }

    /// Листинг кода — это короткие строки; сноска и случайно попавшая в мелкий кегль строка
    /// основного текста — длинные предложения.
    private func looksLikeCode(_ lines: [String]) -> Bool {
        guard lines.count >= 2 else { return false }
        let short = lines.filter { $0.count < 45 }.count
        return short * 100 / lines.count >= 60
    }

    /// Закрывает накопленную сноску и откладывает её до конца абзаца.
    private func flushFootnoteRun() {
        if let text = footnote { footnotes.append(clean(text)) }
        footnote = nil
    }

    private func flushSmall() {
        guard !small.isEmpty else { return }
        if looksLikeCode(small.map(\.text)) {
            // Листинг идёт после абзаца, который его представил: абзац закрываем до него.
            flushParagraph()
            blocks.append("```\n" + small.map(\.text).joined(separator: "\n") + "\n```")
        } else {
            // Сноска и случайно попавшая в мелкий кегль строка основного текста продолжают
            // текущий абзац: принудительный разрыв разорвал бы слово на переносе («Fan-» + «out»).
            for (line, page) in small { appendProse(line, page: page) }
        }
        small = []
    }

    private func startHeading(level: Int, text: String, page: Int) {
        flushSmall()
        flushParagraph()
        if headingLevel != level { flushHeading() }
        headingLevel = level
        if heading == nil { markBlock(page) }
        heading = heading.map { $0 + " " + text } ?? text
    }

    private func appendProse(_ text: String, page: Int) {
        guard let current = paragraph else {
            markBlock(page)
            paragraph = text
            return
        }
        if let merged = mergeBrokenWord(current, text) {
            paragraph = merged
        } else if endsSentence(current) && startsSentence(text) {
            flushParagraph()
            paragraph = text
        } else {
            paragraph = current + " " + text
        }
    }

    /// `trailing` — за строкой на этой странице больше нет основного текста, то есть она лежит
    /// внизу страницы, где стоит сноска. Только тогда мелкий кегль — сноска, а не строка текста,
    /// случайно набранная мелко.
    func add(_ line: Line, page: Int, trailing: Bool = false) {
        if isPageFurniture(line, page: page) { return }
        let size = line.size
        let isSmallLine = size >= 8.8 && size <= 9.3
        if !(isSmallLine && trailing) { flushFootnoteRun() }
        // Маркер списка в этой вёрстке — байт 0x81: строки списков начинаются с него, а не с «•».
        if line.text.unicodeScalars.first?.value == 0x81 {
            let item = line.text.drop { $0.unicodeScalars.first?.value == 0x81 || $0 == " " }
            flushPull(); flushCaption(); flushHeading(); flushSmall(); flushParagraph()
            if !item.isEmpty {
                markBlock(page)
                paragraph = "- " + item
            }
            return
        }
        let isPull = size >= 9.4 && size <= 9.6
        let isSmall = size >= 8.8 && size <= 9.3
        let isCaption = isSmall && line.bold && line.text.hasPrefix("Рис.")
        let continuesCaption = isSmall && !line.bold && caption != nil && !endsSentence(caption ?? "")
        if !isPull { flushPull() }
        if !isCaption && !continuesCaption { flushCaption() }

        switch true {
        case size >= 15.5 && size <= 16.5:
            startHeading(level: 2, text: line.text, page: page)
        case size >= 13.5 && size <= 14.5:
            startHeading(level: 3, text: line.text, page: page)
        case isPull:
            flushHeading(); flushSmall(); flushParagraph()
            if pull == nil { markBlock(page) }
            appendPull(line.text) // эпиграф принципа — отдельный абзац
        case isCaption:
            flushHeading(); flushSmall(); flushParagraph()
            markBlock(page)
            caption = line.text
        case continuesCaption:
            caption = mergeBrokenWord(caption ?? "", line.text) ?? ((caption ?? "") + " " + line.text)
        case isSmall where trailing && line.text.count >= 45:
            // Сноска внизу страницы — это длинное предложение; короткая строка внизу страницы —
            // это листинг, он продолжается на следующей странице. Строки выше дописывают абзац,
            // а сноска уходит после него.
            flushHeading(); flushSmall()
            footnote = mergeBrokenWord(footnote ?? "", line.text) ?? ((footnote ?? "") + " " + line.text)
        case isSmall:
            flushHeading()
            small.append((text: line.text, page: page))
        case size >= 10.05 && size <= 11.6 && !line.bold:
            flushHeading(); flushSmall()
            appendProse(line.text, page: page)
        default:
            return // номера частей и глав, подписи на рисунках, надстрочные знаки сносок
        }
    }

    func build() -> String {
        flushHeading()
        flushPull()
        flushCaption()
        flushSmall()
        flushFootnoteRun()
        flushParagraph()
        return (["# \(title)"] + blocks).joined(separator: "\n\n") + "\n"
    }
}

var totalChars = 0
for chapter in chapters {
    let builder = Builder(title: chapter.title)
    for page in chapter.from...chapter.to {
        guard let pdfPage = document.page(at: page - 1) else { continue }
        let lines = pageLines(pdfPage)
        // За строкой больше нет основного текста на странице — она в нижней части страницы.
        var bodyAfter = [Bool](repeating: false, count: lines.count)
        var seenBody = false
        for index in stride(from: lines.count - 1, through: 0, by: -1) {
            bodyAfter[index] = seenBody
            seenBody = seenBody || isBodyLine(lines[index], page: page)
        }
        for (index, line) in lines.enumerated() {
            builder.add(line, page: page, trailing: !bodyAfter[index])
        }
    }
    let markdown = builder.build()
    let url = URL(fileURLWithPath: outDir).appendingPathComponent(chapter.file)
    try markdown.write(to: url, atomically: true, encoding: .utf8)
    let headings = markdown.split(separator: "\n").filter { $0.hasPrefix("#") }
    print("\(chapter.file): \(markdown.count) символов, заголовков: \(headings.count) (p\(chapter.from)–\(chapter.to))")
    for item in headings { print("   \(item)") }
    totalChars += markdown.count
}
print("всего символов: \(totalChars)")
