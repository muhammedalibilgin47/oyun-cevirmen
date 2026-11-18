import SwiftUI
import Speech
import AVFoundation
import UIKit

// MARK: - Uygulama

@main
struct OyunCevirmenApp: App {
    @StateObject private var engine = Engine()
    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(engine)
                .preferredColorScheme(.dark)
        }
    }
}

// MARK: - Modeller

struct Line: Identifiable {
    let id = UUID()
    let tag: String
    let tr: String
    let src: String
}

struct Lang: Identifiable {
    let id: String   // konuşma tanıma dil kodu
    let name: String
}

let languages: [Lang] = [
    Lang(id: "en-US", name: "İngilizce (GTA 6, çoğu oyun)"),
    Lang(id: "en-GB", name: "İngilizce (İngiliz aksanı)"),
    Lang(id: "ja-JP", name: "Japonca"),
    Lang(id: "es-ES", name: "İspanyolca"),
    Lang(id: "fr-FR", name: "Fransızca"),
    Lang(id: "de-DE", name: "Almanca"),
    Lang(id: "it-IT", name: "İtalyanca"),
    Lang(id: "pt-BR", name: "Portekizce"),
    Lang(id: "ru-RU", name: "Rusça"),
    Lang(id: "ko-KR", name: "Korece"),
    Lang(id: "zh-CN", name: "Çince"),
    Lang(id: "pl-PL", name: "Lehçe")
]

struct VoiceProfile {
    let name: String
    let pitch: Float
    let rate: Float
}

let profiles: [VoiceProfile] = [
    VoiceProfile(name: "Ana karakter", pitch: 0.85, rate: 1.00),
    VoiceProfile(name: "Kadın karakter", pitch: 1.25, rate: 1.02),
    VoiceProfile(name: "Sert NPC", pitch: 0.65, rate: 0.95),
    VoiceProfile(name: "Genç NPC", pitch: 1.10, rate: 1.10),
    VoiceProfile(name: "Yaşlı NPC", pitch: 0.75, rate: 0.88),
    VoiceProfile(name: "Telsiz / Polis", pitch: 1.00, rate: 1.12)
]

// MARK: - Motor (dinle → çevir → oku)

final class Engine: NSObject, ObservableObject, AVSpeechSynthesizerDelegate {
    // Ekran durumu
    @Published var running = false
    @Published var status = "Hazır"
    @Published var current = "Başlat'a basın, telefonu TV / monitör hoparlörüne yakın tutun."
    @Published var currentSrc = ""
    @Published var interim = ""
    @Published var log: [Line] = []
    @Published var errorText = ""

    // Ayarlar (kalıcı)
    private let ud = UserDefaults.standard
    @Published var srcLang: String { didSet { ud.set(srcLang, forKey: "srcLang"); if running { startTask() } } }
    @Published var ttsOn: Bool { didSet { ud.set(ttsOn, forKey: "ttsOn") } }
    @Published var npcMode: Bool { didSet { ud.set(npcMode, forKey: "npcMode") } }
    @Published var headphones: Bool { didSet { ud.set(headphones, forKey: "headphones") } }
    @Published var skipOld: Bool { didSet { ud.set(skipOld, forKey: "skipOld") } }
    @Published var rate: Double { didSet { ud.set(rate, forKey: "rate") } }
    @Published var volume: Double { didSet { ud.set(volume, forKey: "volume") } }
    @Published var voiceId: String { didSet { ud.set(voiceId, forKey: "voiceId") } }

    // İç durum
    private let audioEngine = AVAudioEngine()
    private var recognizer: SFSpeechRecognizer?
    private var request: SFSpeechAudioBufferRecognitionRequest?
    private var task: SFSpeechRecognitionTask?
    private let synth = AVSpeechSynthesizer()
    private var generation = 0
    private var silenceTimer: Timer?
    private var lastPartial = ""
    private var lastFinal = ""
    private var npcIndex = 0
    private var pending = 0
    private var listening = true
    private var cache: [String: String] = [:]

    var trVoices: [AVSpeechSynthesisVoice] {
        AVSpeechSynthesisVoice.speechVoices()
            .filter { $0.language.hasPrefix("tr") }
            .sorted { $0.quality.rawValue > $1.quality.rawValue }
    }

    override init() {
        srcLang = ud.string(forKey: "srcLang") ?? "en-US"
        ttsOn = ud.object(forKey: "ttsOn") as? Bool ?? true
        npcMode = ud.object(forKey: "npcMode") as? Bool ?? true
        headphones = ud.object(forKey: "headphones") as? Bool ?? false
        skipOld = ud.object(forKey: "skipOld") as? Bool ?? true
        rate = ud.object(forKey: "rate") as? Double ?? 1.1
        volume = ud.object(forKey: "volume") as? Double ?? 1.0
        voiceId = ud.string(forKey: "voiceId") ?? ""
        super.init()
        synth.delegate = self
        if voiceId.isEmpty, let v = trVoices.first { voiceId = v.identifier }
        if trVoices.isEmpty {
            errorText = "Türkçe ses bulunamadı. Ayarlar > Erişilebilirlik > Seslendirilen İçerik > Sesler > Türkçe bölümünden bir ses indirin (\"Gelişmiş\" olanlar daha doğal)."
        }
    }

    // MARK: Başlat / durdur

    func start() {
        errorText = ""
        SFSpeechRecognizer.requestAuthorization { st in
            DispatchQueue.main.async {
                guard st == .authorized else {
                    self.errorText = "Konuşma tanıma izni verilmedi. Ayarlar > Oyun Çevirmen bölümünden izin verin."
                    return
                }
                AVAudioSession.sharedInstance().requestRecordPermission { ok in
                    DispatchQueue.main.async {
                        if ok { self.begin() } else { self.errorText = "Mikrofon izni verilmedi. Ayarlar > Oyun Çevirmen bölümünden izin verin." }
                    }
                }
            }
        }
    }

    private func begin() {
        do {
            let s = AVAudioSession.sharedInstance()
            try s.setCategory(.playAndRecord, mode: .default, options: [.defaultToSpeaker, .allowBluetoothA2DP])
            try s.setActive(true)
            let input = audioEngine.inputNode
            let fmt = input.outputFormat(forBus: 0)
            input.removeTap(onBus: 0)
            input.installTap(onBus: 0, bufferSize: 1024, format: fmt) { [weak self] buf, _ in
                guard let self = self, self.listening else { return }
                self.request?.append(buf)
            }
            audioEngine.prepare()
            try audioEngine.start()
            running = true
            npcIndex = 0
            lastFinal = ""
            UIApplication.shared.isIdleTimerDisabled = true
            startTask()
        } catch {
            errorText = "Ses başlatılamadı: \(error.localizedDescription)"
        }
    }

    func stop() {
        running = false
        generation += 1
        silenceTimer?.invalidate()
        task?.cancel(); task = nil
        request?.endAudio(); request = nil
        audioEngine.stop()
        audioEngine.inputNode.removeTap(onBus: 0)
        synth.stopSpeaking(at: .immediate)
        pending = 0
        listening = true
        interim = ""
        status = "Durduruldu"
        UIApplication.shared.isIdleTimerDisabled = false
    }

    // MARK: Konuşma tanıma

    private func startTask() {
        task?.cancel(); task = nil
        request?.endAudio()
        guard running else { return }
        recognizer = SFSpeechRecognizer(locale: Locale(identifier: srcLang))
        guard let rec = recognizer, rec.isAvailable else {
            errorText = "Bu dil için konuşma tanıma şu an kullanılamıyor. İnternet bağlantısını kontrol edin."
            return
        }
        let req = SFSpeechAudioBufferRecognitionRequest()
        req.shouldReportPartialResults = true
        if #available(iOS 16.0, *) { req.addsPunctuation = true }
        request = req
        generation += 1
        let gen = generation
        lastPartial = ""
        status = "Dinliyor…"

        task = rec.recognitionTask(with: req) { [weak self] result, err in
            DispatchQueue.main.async {
                guard let self = self, gen == self.generation, self.running else { return }
                if let r = result {
                    let text = r.bestTranscription.formattedString
                    self.interim = text
                    self.lastPartial = text
                    self.silenceTimer?.invalidate()
                    if r.isFinal {
                        self.commit()
                        return
                    }
                    // 1,1 sn yeni kelime gelmezse cümle bitti say
                    self.silenceTimer = Timer.scheduledTimer(withTimeInterval: 1.1, repeats: false) { [weak self] _ in
                        self?.commit()
                    }
                }
                if err != nil {
                    if !self.lastPartial.isEmpty {
                        self.commit()
                    } else {
                        DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) {
                            if gen == self.generation && self.running { self.startTask() }
                        }
                    }
                }
            }
        }
    }

    private func commit() {
        silenceTimer?.invalidate()
        let text = lastPartial.trimmingCharacters(in: .whitespacesAndNewlines)
        lastPartial = ""
        interim = ""
        startTask()
        guard text.count > 1, text != lastFinal else { return }
        lastFinal = text
        translate(text) { [weak self] tr in
            guard let self = self else { return }
            guard let tr = tr else {
                self.errorText = "Çeviri alınamadı, internet bağlantısını kontrol edin."
                return
            }
            self.errorText = ""
            self.current = tr
            self.currentSrc = text
            let tag = self.speak(tr)
            self.addLog(tag, tr, text)
        }
    }

    // MARK: Çeviri

    private func translate(_ text: String, completion: @escaping (String?) -> Void) {
        let sl = srcLang.hasPrefix("zh") ? "zh-CN" : String(srcLang.prefix(2))
        let key = sl + "|" + text
        if let c = cache[key] { completion(c); return }

        var comps = URLComponents(string: "https://translate.googleapis.com/translate_a/single")!
        comps.queryItems = [
            URLQueryItem(name: "client", value: "gtx"),
            URLQueryItem(name: "sl", value: sl),
            URLQueryItem(name: "tl", value: "tr"),
            URLQueryItem(name: "dt", value: "t"),
            URLQueryItem(name: "q", value: text)
        ]
        guard let url = comps.url else { completion(nil); return }
        URLSession.shared.dataTask(with: url) { data, _, _ in
            var out: String? = nil
            if let d = data,
               let json = try? JSONSerialization.jsonObject(with: d) as? [Any],
               let parts = json.first as? [Any] {
                out = parts.compactMap { ($0 as? [Any])?.first as? String }.joined()
            }
            DispatchQueue.main.async {
                if let o = out, !o.isEmpty {
                    self.store(key, o)
                    completion(o)
                } else {
                    self.fallbackTranslate(text, sl: sl, key: key, completion: completion)
                }
            }
        }.resume()
    }

    private func fallbackTranslate(_ text: String, sl: String, key: String, completion: @escaping (String?) -> Void) {
        var comps = URLComponents(string: "https://api.mymemory.translated.net/get")!
        comps.queryItems = [
            URLQueryItem(name: "q", value: text),
            URLQueryItem(name: "langpair", value: sl + "|tr")
        ]
        guard let url = comps.url else { completion(nil); return }
        URLSession.shared.dataTask(with: url) { data, _, _ in
            var out: String? = nil
            if let d = data,
               let json = try? JSONSerialization.jsonObject(with: d) as? [String: Any],
               let rd = json["responseData"] as? [String: Any] {
                out = rd["translatedText"] as? String
            }
            DispatchQueue.main.async {
                if let o = out, !o.isEmpty { self.store(key, o) }
                completion(out)
            }
        }.resume()
    }

    private func store(_ key: String, _ value: String) {
        if cache.count > 500 { cache.removeAll() }
        cache[key] = value
    }

    // MARK: Türkçe seslendirme

    @discardableResult
    func speak(_ text: String) -> String {
        guard ttsOn else { return "Altyazı" }
        if skipOld && pending >= 2 {
            synth.stopSpeaking(at: .immediate)
            pending = 0
        }
        let u = AVSpeechUtterance(string: text)
        let base = Float(rate)
        let voices = trVoices
        let chosen = voices.first { $0.identifier == voiceId } ?? AVSpeechSynthesisVoice(language: "tr-TR")
        var tag = "Anlatıcı"
        if npcMode {
            let p = profiles[npcIndex % profiles.count]
            u.voice = voices.isEmpty ? chosen : voices[npcIndex % voices.count]
            u.pitchMultiplier = p.pitch
            u.rate = min(AVSpeechUtteranceMaximumSpeechRate, AVSpeechUtteranceDefaultSpeechRate * base * p.rate)
            tag = p.name
            npcIndex += 1
        } else {
            u.voice = chosen
            u.pitchMultiplier = 1.0
            u.rate = min(AVSpeechUtteranceMaximumSpeechRate, AVSpeechUtteranceDefaultSpeechRate * base)
        }
        u.volume = Float(volume)
        pending += 1
        synth.speak(u)
        return tag
    }

    func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didStart utterance: AVSpeechUtterance) {
        DispatchQueue.main.async {
            // Kulaklık yoksa okurken mikrofonu dinleme (yankı olmasın)
            if !self.headphones && self.running {
                self.listening = false
                self.status = "Türkçe okunuyor…"
            }
        }
    }

    func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didFinish utterance: AVSpeechUtterance) {
        DispatchQueue.main.async { self.utteranceDone() }
    }

    func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didCancel utterance: AVSpeechUtterance) {
        DispatchQueue.main.async { self.utteranceDone() }
    }

    private func utteranceDone() {
        pending = max(0, pending - 1)
        if !synth.isSpeaking && pending == 0 && !listening {
            listening = true
            if running { startTask() }
        }
    }

    // MARK: Yardımcılar

    private func addLog(_ tag: String, _ tr: String, _ src: String) {
        log.insert(Line(tag: tag, tr: tr, src: src), at: 0)
        if log.count > 40 { log.removeLast(log.count - 40) }
    }

    func test() {
        let wasOn = ttsOn
        ttsOn = true
        let lines = [
            "Hey, arabaya bin, polisler geliyor!",
            "Bunu yapmak zorunda değiliz, biliyorsun.",
            "Kimse kıpırdamasın!",
            "Bana bak evlat, bu şehir seni yer bitirir."
        ]
        for l in lines { addLog(speak(l), l, "(ses testi)") }
        ttsOn = wasOn
    }
}

// MARK: - Ekranlar

struct ContentView: View {
    @EnvironmentObject var e: Engine
    @State private var full = false

    var body: some View {
        if full {
            FullView(full: $full)
        } else {
            NavigationView {
                Form {
                    Section {
                        VStack(alignment: .leading, spacing: 8) {
                            Text(e.current)
                                .font(.system(size: 24, weight: .bold))
                                .fixedSize(horizontal: false, vertical: true)
                            if !e.currentSrc.isEmpty {
                                Text(e.currentSrc).font(.footnote).foregroundColor(.secondary)
                            }
                            if !e.interim.isEmpty {
                                Text(e.interim).font(.footnote).italic().foregroundColor(.gray)
                            }
                            Text(e.status).font(.caption).foregroundColor(e.running ? .green : .secondary)
                        }
                        .padding(.vertical, 6)

                        Button(action: { e.running ? e.stop() : e.start() }) {
                            Text(e.running ? "■ Durdur" : "▶ Başlat")
                                .font(.headline)
                                .frame(maxWidth: .infinity)
                        }
                        .foregroundColor(e.running ? .red : .pink)

                        Button("⛶ Tam ekran altyazı") { full = true }
                        Button("🔊 Ses testi") { e.test() }

                        if !e.errorText.isEmpty {
                            Text(e.errorText).font(.footnote).foregroundColor(.orange)
                        }
                    }

                    Section(header: Text("Oyunun dili")) {
                        Picker("Konuşma dili", selection: $e.srcLang) {
                            ForEach(languages) { l in Text(l.name).tag(l.id) }
                        }
                    }

                    Section(header: Text("Türkçe ses (dublaj)"),
                            footer: Text("Karakter modunda her replik farklı ton ve hızla okunur. Ayarlar'dan birden fazla Türkçe \"Gelişmiş\" ses indirirseniz çeşitlilik artar ve ses daha doğal olur.")) {
                        Toggle("Sesli okuma", isOn: $e.ttsOn)
                        Toggle("Her replik farklı karakter sesi", isOn: $e.npcMode)
                        Picker("Ana ses", selection: $e.voiceId) {
                            ForEach(e.trVoices, id: \.identifier) { v in
                                Text(v.name + (v.quality == .enhanced ? " (Gelişmiş)" : "")).tag(v.identifier)
                            }
                        }
                        HStack { Text("Hız"); Slider(value: $e.rate, in: 0.7...1.6) }
                        HStack { Text("Ses"); Slider(value: $e.volume, in: 0.2...1.0) }
                        Toggle("Kulaklık takıyorum", isOn: $e.headphones)
                        Toggle("Gecikirse eski replikleri atla", isOn: $e.skipOld)
                    }

                    Section(header: Text("Son replikler")) {
                        if e.log.isEmpty {
                            Text("Henüz bir şey yok.").foregroundColor(.secondary)
                        }
                        ForEach(e.log) { l in
                            VStack(alignment: .leading, spacing: 2) {
                                Text(l.tag).font(.caption).foregroundColor(.pink)
                                Text(l.tr)
                                Text(l.src).font(.caption).foregroundColor(.secondary)
                            }
                        }
                    }

                    Section(header: Text("İpuçları")) {
                        Text("• Telefonu TV / monitör hoparlörüne 0,5–1 m yakın koyun.\n• Oyunda müziği ve efektleri biraz kısın, diyalog sesini açın.\n• En iyisi: Bluetooth kulaklık takıp \"Kulaklık takıyorum\"u açın.\n• İnternet bağlantısı gerekir.")
                            .font(.footnote)
                            .foregroundColor(.secondary)
                    }
                }
                .navigationTitle("Oyun Çevirmen")
            }
            .navigationViewStyle(.stack)
        }
    }
}

struct FullView: View {
    @EnvironmentObject var e: Engine
    @Binding var full: Bool

    var body: some View {
        ZStack(alignment: .bottomLeading) {
            Color.black.ignoresSafeArea()
            VStack(alignment: .leading, spacing: 10) {
                Spacer()
                Text(e.current)
                    .font(.system(size: 44, weight: .bold))
                    .foregroundColor(.white)
                    .minimumScaleFactor(0.4)
                Text(e.currentSrc).foregroundColor(.gray)
                Text(e.interim).italic().foregroundColor(Color.gray.opacity(0.6))
                Text("Çıkmak için dokunun").font(.caption2).foregroundColor(Color.gray.opacity(0.4))
            }
            .padding(24)
        }
        .contentShape(Rectangle())
        .onTapGesture { full = false }
        .statusBar(hidden: true)
    }
}
