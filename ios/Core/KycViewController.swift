import UIKit
import AVFoundation

/** Present this controller; all KYC calls originate from the app with one API token. */
@MainActor public final class KycViewController: UIViewController {
    public var onComplete: ((KycResult) -> Void)?
    private let options: KycOptions
    private let session: KycSession
    private let text: KycText
    private let camera = CameraSource()
    private let chip = ChipReader()
    private let heading = UILabel(), hint = UILabel()
    private let button = UIButton(type:.system), cancel = UIButton(type:.system)
    private let guide = CameraGuide(frame:.zero)
    private let choices = UIStackView()
    private var buttonAction: (() -> Void)?
    private var task: Task<Void,Never>?
    private var state = "welcome", busy = false, analysing = false
    private var config: [String:Any] = [:]
    private var document = "tr_id_card", side = "front"
    private var mrz: MRZAccess?, latest: UIImage?, selfie: UIImage?
    private var stableSince = 0, stableMrz = ""
    private var engine: ActiveChallenge?
    private var challenge: [String:Any] = [:]
    private var liveStart = 0, lastSample = 0
    private var frames: [Int:[Int:[String:Any]]] = [:]
    public init(options: KycOptions) {
        self.options=options;session=KycSession(options);text=KycText(language:options.language)
        super.init(nibName:nil,bundle:nil);modalPresentationStyle = .fullScreen
    }
    required init?(coder: NSCoder) { fatalError("Use init(options:)") }
    public override var supportedInterfaceOrientations: UIInterfaceOrientationMask { .portrait }
    public override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor=UIColor(red:0.05,green:0.09,blue:0.15,alpha:1)
        let brand=UILabel();brand.text="LEGICHAIN";brand.textColor=UIColor(red:0.45,green:0.88,blue:0.77,alpha:1)
        brand.font = .systemFont(ofSize:14,weight:.bold)
        heading.font = .preferredFont(forTextStyle:.title1);heading.textColor = .white;heading.numberOfLines=0
        hint.font = .preferredFont(forTextStyle:.body);hint.textColor=UIColor(white:0.8,alpha:1);hint.numberOfLines=0
        choices.axis = .vertical;choices.spacing=8
        guide.preview.session=camera.session
        button.titleLabel?.font = .preferredFont(forTextStyle:.headline);button.addTarget(self,action:#selector(tap),for:.touchUpInside)
        cancel.setTitle(text["cancel"],for:.normal);cancel.addTarget(self,action:#selector(cancelFlow),for:.touchUpInside)
        let stack=UIStackView(arrangedSubviews:[brand,heading,hint,guide,choices,button,cancel]);stack.axis = .vertical;stack.spacing=18
        stack.translatesAutoresizingMaskIntoConstraints=false;view.addSubview(stack)
        NSLayoutConstraint.activate([
            stack.leadingAnchor.constraint(equalTo:view.safeAreaLayoutGuide.leadingAnchor,constant:24),
            stack.trailingAnchor.constraint(equalTo:view.safeAreaLayoutGuide.trailingAnchor,constant:-24),
            stack.topAnchor.constraint(equalTo:view.safeAreaLayoutGuide.topAnchor,constant:20),
            stack.bottomAnchor.constraint(equalTo:view.safeAreaLayoutGuide.bottomAnchor,constant:-16),
            button.heightAnchor.constraint(greaterThanOrEqualToConstant:52),guide.heightAnchor.constraint(greaterThanOrEqualToConstant:150)
        ])
        camera.onFrame={ [weak self] image in self?.analyse(image) }
        camera.onError={ [weak self] in self?.showError("permission") { [weak self] in self?.chooseDocument() } }
        NotificationCenter.default.addObserver(self,selector:#selector(backgrounded),name:UIApplication.didEnterBackgroundNotification,object:nil)
        heading.text=text["welcome"];hint.text=text["intro"];guide.isHidden=true
        action("start") { [weak self] in self?.begin() }
    }
    @objc private func tap() { buttonAction?() }
    @objc private func cancelFlow() { finish("cancelled") }
    private func action(_ key: String,_ callback: @escaping () -> Void) {
        button.isHidden=false;button.isEnabled=true;button.setTitle(text[key],for:.normal);buttonAction=callback
    }
    private func begin() {
        step(retry:{ [weak self] in self?.begin() }) {
            self.config=try await self.session.create()
            let granted=await AVCaptureDevice.requestAccess(for:.video)
            if granted { self.chooseDocument() } else { self.showError("permission") { [weak self] in
                if let url=URL(string:UIApplication.openSettingsURLString) { UIApplication.shared.open(url) };self?.chooseDocument()
            } }
        }
    }
    private func chooseDocument() {
        busy=false;state="select";heading.text=text["select"];hint.text="";guide.isHidden=true;button.isHidden=true
        clearChoices()
        for kind in config["document_type_allowed"] as? [String] ?? [] {
            let choice=UIButton(type:.system);choice.setTitle(text[kind],for:.normal)
            choice.addAction(UIAction { [weak self] _ in
                guard let self else { return };self.document=kind;self.mrz=nil
                self.side=["passport","uk_passport"].contains(kind) ? "single" : "front";self.documentScreen()
            },for:.touchUpInside)
            choices.addArrangedSubview(choice)
        }
    }
    private func clearChoices() { choices.arrangedSubviews.forEach { choices.removeArrangedSubview($0);$0.removeFromSuperview() } }
    private func documentScreen() {
        state="document";busy=false;stableSince=0;stableMrz="";latest=nil;clearChoices()
        guide.isHidden=false;guide.preview.isHidden=false;guide.mode="document";guide.stopAnimation()
        heading.text=text[side];hint.text=text["frame"];camera.start(front:false)
        action("capture") { [weak self] in
            guard let self,let image=self.latest else { return }
            if self.side != "front" && self.config["nfc_required"] as? Bool == true && self.mrz == nil { return }
            self.uploadDocument(image)
        }
    }
    private func uploadDocument(_ image: UIImage) {
        if busy { return }
        let body: [String:Any]=["document_type":document,"side":side,"mime_type":"image/jpeg","image_b64":encoded(image)]
        step(retry:{ [weak self] in self?.uploadDocument(image) }) {
            try await self.session.evidence("documents",body:body,slot:"document:\(self.side)")
            if self.side == "front" { self.side="back";self.documentScreen() }
            else if self.config["nfc_required"] as? Bool == true { self.nfcScreen() } else { self.afterChip() }
        }
    }
    private func nfcScreen() {
        state="nfc";busy=false;camera.stop();guide.preview.isHidden=true;guide.mode="nfc";guide.animateNfc()
        heading.text=text["nfc"];hint.text=text["nfc_hint"]
        action("nfc_read") { [weak self] in
            guard let self else { return }
            guard self.chip.available else { self.hint.text=self.text["nfc_missing"];return }
            guard let access=self.mrz else { self.documentScreen();return }
            self.step(retry:{ [weak self] in self?.nfcScreen() }) {
                let body=try await self.chip.read(access) { self.text["nfc_busy"] }
                try Task.checkCancellation()
                self.uploadNfc(body)
            }
        }
    }
    private func uploadNfc(_ body: [String:Any]) {
        step(retry:{ [weak self] in self?.uploadNfc(body) }) { try await self.session.evidence("nfc",body:body);self.afterChip() }
    }
    private func uploadLiveness(_ body: [String:Any]) {
        step(retry:{ [weak self] in self?.uploadLiveness(body) }) {
            try await self.session.evidence("liveness",body:body);self.frames.removeAll();self.submitScreen()
        }
    }
    private func afterChip() {
        if (config["liveness_required"] as? Bool ?? true) || (config["face_match_required"] as? Bool ?? true) { selfieScreen() }
        else { submitScreen() }
    }
    private func selfieScreen() {
        state="selfie";busy=false;stableSince=0;clearChoices();guide.isHidden=false;guide.preview.isHidden=false;guide.mode="face";guide.stopAnimation()
        heading.text=text["selfie"];hint.text=text["neutral"];button.isHidden=true;camera.start(front:true)
    }
    private func uploadSelfie(_ image: UIImage) {
        if busy { return };selfie=image
        step(retry:{ [weak self] in self?.uploadSelfie(image) }) {
            try await self.session.evidence("selfie",body:["mime_type":"image/jpeg","image_b64":self.encoded(image)])
            if self.config["liveness_required"] as? Bool ?? true { try await self.beginActive() } else {
                try await self.session.evidence("liveness",body:["mode":"passive","frame_b64":self.encoded(image),"frame_mime_type":"image/jpeg"])
                self.submitScreen()
            }
        }
    }
    private var now: Int { Int(ProcessInfo.processInfo.systemUptime*1000) }
    private func beginActive() async throws {
        challenge=try await session.challenge()
        guard let sequence=challenge["sequence"] as? [String] else { throw FlowError.invalidResponse }
        liveStart=now;engine=try ActiveChallenge(sequence:sequence,started:liveStart);frames.removeAll();lastSample=0
        busy=false;state="active";button.isHidden=true;heading.text=text["active"];hint.text=text["prepare"]
    }
    private func submitScreen() {
        step(retry:{ [weak self] in self?.submitScreen() }) {
            _ = try await self.session.submit();self.state="submitted";self.busy=false;self.camera.stop();self.guide.isHidden=true;self.clearChoices()
            self.heading.text=self.text["submitted"];self.hint.text=self.text["submitted_hint"]
            self.action("done") { [weak self] in self?.finish("submitted") }
        }
    }
    private func analyse(_ image: UIImage) {
        guard !busy,!analysing,["document","selfie","active"].contains(state) else { return }
        analysing=true;let isDocument=state=="document"
        Task {
            defer { analysing=false }
            do {
                let result=try await Task.detached(priority:.userInitiated) { try FrameAnalysis.analyse(image,document:isDocument) }.value
                guard !busy,["document","selfie","active"].contains(state) else { return }
                if state=="document" {
                    let scale=max(guide.bounds.width/image.size.width,guide.bounds.height/image.size.height)
                    let dx=(guide.bounds.width-image.size.width*scale)/2,dy=(guide.bounds.height-image.size.height*scale)/2
                    let rect=result.document.map { CGRect(x:dx+$0.minX*image.size.width*scale,y:dy+(1-$0.maxY)*image.size.height*scale,
                                                          width:$0.width*image.size.width*scale,height:$0.height*image.size.height*scale) }
                    let quality=rect.map { guide.guideRect.insetBy(dx:4,dy:4).contains($0) } ?? false
                    latest=quality ? image : nil;button.isEnabled=quality
                    if quality,let parsed=result.mrz {
                        if stableMrz != parsed.raw { stableMrz=parsed.raw;stableSince=now }
                        mrz=parsed;hint.text=text["steady"]
                        if now-stableSince>900 { uploadDocument(image) }
                    } else { stableMrz="";stableSince=0;hint.text=text["frame"] }
                } else if state=="selfie" {
                    if result.face?.neutral==true,self.faceInside(result.faceRect,image:image) {
                        if stableSince==0 { stableSince=now };if now-stableSince>1000 { uploadSelfie(image) }
                    } else { stableSince=0 }
                } else { liveFrame(image,faceInside(result.faceRect,image:image) ? result.face : nil) }
            } catch { hint.text=text["error"] }
        }
    }
    private func faceInside(_ rect: CGRect?,image: UIImage) -> Bool {
        guard let rect,rect.width>0.25 else { return false }
        let scale=max(guide.bounds.width/image.size.width,guide.bounds.height/image.size.height)
        let dx=(guide.bounds.width-image.size.width*scale)/2,dy=(guide.bounds.height-image.size.height*scale)/2
        return guide.guideRect.contains(CGRect(x:dx+rect.minX*image.size.width*scale,y:dy+(1-rect.maxY)*image.size.height*scale,
                                               width:rect.width*image.size.width*scale,height:rect.height*image.size.height*scale))
    }
    private func liveFrame(_ image: UIImage,_ face: ActiveChallenge.Face?) {
        guard let engine else { return }
        let before=engine.phase,index=engine.index,time=now;engine.update(now:time,face:face)
        let changed=before != engine.phase
        if face != nil && (changed || time-lastSample>600) && ([ActiveChallenge.Phase.perform,.returnToCenter].contains(before) || engine.phase == .perform) {
            let slot: Int
            if changed && engine.phase == .perform { slot=0 }
            else if changed && engine.phase == .returnToCenter { slot=3 }
            else if changed && before == .returnToCenter { slot=5 }
            else if engine.phase == .returnToCenter { slot=4 }
            else { slot=frames[index]?[1] == nil ? 1 : 2 }
            frames[index,default:[:]][slot]=["image_b64":encoded(image,width:640,quality:0.75),"timestamp_ms":time-liveStart];lastSample=time
        }
        heading.text="\(min(index+1,engine.count)) / \(engine.count) · \(text[engine.action])"
        switch engine.phase {
        case .instruction: hint.text=text["prepare"]
        case .ready: hint.text=text["neutral"]
        case .perform: hint.text=text["go"];if changed { UIImpactFeedbackGenerator(style:.medium).impactOccurred() }
        case .returnToCenter: hint.text=text["return"]
        case .failed: showError("retry_live") { [weak self] in self?.restartActive() }
        case .complete:
            guard let selfie,let token=challenge["challenge_token"] as? String else { return }
            let samples=frames.values.flatMap { $0.values }.sorted { ($0["timestamp_ms"] as? Int ?? 0)<($1["timestamp_ms"] as? Int ?? 0) }
            let body: [String:Any]=["mode":"active","frame_b64":encoded(selfie),"frame_mime_type":"image/jpeg",
                                   "challenge_token":token,"completed_actions":engine.records.map(\.json),"frames":samples]
            uploadLiveness(body)
        }
    }
    private func restartActive() { step(retry:{ [weak self] in self?.selfieScreen() }) { try await self.beginActive() } }
    private func encoded(_ image: UIImage,width: CGFloat=1600,quality: CGFloat=0.88) -> String {
        let scale=min(1,width/image.size.width),size=CGSize(width:image.size.width*scale,height:image.size.height*scale)
        let format=UIGraphicsImageRendererFormat();format.scale=1
        let rendered=UIGraphicsImageRenderer(size:size,format:format).image { _ in image.draw(in:CGRect(origin:.zero,size:size)) }
        return rendered.jpegData(compressionQuality:quality)?.base64EncodedString() ?? ""
    }
    private func step(retry: @escaping () -> Void, work: @escaping () async throws -> Void) {
        busy=true;button.isEnabled=false;hint.text=text["busy"]
        task=Task {
            do { try await work() } catch is CancellationError { }
            catch FlowError.operationFailed(let evidence) {
                showError("error") { [weak self] in
                    guard let self else { return }
                    switch evidence { case "documents": self.documentScreen();case "selfie": self.selfieScreen()
                    case "nfc": self.nfcScreen()
                    default:
                        if self.config["liveness_required"] as? Bool ?? true { self.restartActive() }
                        else { self.selfieScreen() }
                    }
                }
            } catch { showError("error",retry:retry) }
        }
    }
    private func showError(_ key: String,retry: @escaping () -> Void) { state="error";busy=false;hint.text=text[key];action("retry",retry) }
    @objc private func backgrounded() {
        if state=="active" { engine=nil;frames.removeAll();showError("retry_live") { [weak self] in self?.restartActive() } }
    }
    private func finish(_ status: String) {
        task?.cancel();camera.stop();session.close();frames.removeAll();selfie=nil;latest=nil;guide.stopAnimation()
        let result=KycResult(status:status,applicationId:session.applicationId)
        let completion = onComplete
        dismiss(animated:true) { completion?(result) }
    }
    deinit { NotificationCenter.default.removeObserver(self);task?.cancel();camera.stop() }
}
