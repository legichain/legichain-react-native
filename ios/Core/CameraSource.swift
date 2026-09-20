import AVFoundation
import UIKit
import Vision

final class CameraSource: NSObject, AVCaptureVideoDataOutputSampleBufferDelegate {
    let session = AVCaptureSession()
    let queue = DispatchQueue(label: "com.legichain.camera")
    var onFrame: ((UIImage) -> Void)?
    var onError: (() -> Void)?
    private let context = CIContext()
    private var lastFrame: CFTimeInterval = 0
    func start(front: Bool) {
        queue.async {
            self.session.beginConfiguration()
            self.session.sessionPreset = .hd1280x720
            for input in self.session.inputs { self.session.removeInput(input) }
            for output in self.session.outputs { self.session.removeOutput(output) }
            guard let device = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: front ? .front : .back),
                  let input = try? AVCaptureDeviceInput(device: device), self.session.canAddInput(input) else {
                self.session.commitConfiguration(); DispatchQueue.main.async { self.onError?() }; return
            }
            self.session.addInput(input)
            let output = AVCaptureVideoDataOutput()
            output.alwaysDiscardsLateVideoFrames = true
            output.videoSettings = [kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA]
            output.setSampleBufferDelegate(self, queue: self.queue)
            guard self.session.canAddOutput(output) else { self.session.commitConfiguration(); DispatchQueue.main.async { self.onError?() }; return }
            self.session.addOutput(output)
            if let connection = output.connection(with: .video) {
                connection.videoOrientation = .portrait
                if connection.isVideoMirroringSupported { connection.automaticallyAdjustsVideoMirroring = false; connection.isVideoMirrored = front }
            }
            self.session.commitConfiguration()
            if !self.session.isRunning { self.session.startRunning() }
        }
    }
    func stop() { queue.async { if self.session.isRunning { self.session.stopRunning() } } }
    func captureOutput(_ output: AVCaptureOutput, didOutput buffer: CMSampleBuffer, from connection: AVCaptureConnection) {
        let now = CACurrentMediaTime()
        guard now - lastFrame >= 0.12, let pixels = CMSampleBufferGetImageBuffer(buffer) else { return }
        lastFrame = now
        let ci = CIImage(cvPixelBuffer: pixels)
        guard let cg = context.createCGImage(ci, from: ci.extent) else { return }
        let image = UIImage(cgImage: cg)
        DispatchQueue.main.async { self.onFrame?(image) }
    }
}

final class CameraGuide: UIView {
    var mode = "document" { didSet { setNeedsDisplay() } }
    let preview = AVCaptureVideoPreviewLayer()
    private let outline = CAShapeLayer()
    private var timer: Timer?
    override init(frame: CGRect) {
        super.init(frame: frame);preview.videoGravity = .resizeAspectFill;layer.addSublayer(preview)
        outline.strokeColor=UIColor(red:0.45,green:0.88,blue:0.77,alpha:1).cgColor;outline.fillColor=UIColor.clear.cgColor
        outline.lineWidth=3;layer.addSublayer(outline);backgroundColor = .clear
    }
    required init?(coder: NSCoder) { fatalError("init(coder:) is unavailable") }
    override func layoutSubviews() { super.layoutSubviews(); preview.frame = bounds; setNeedsDisplay() }
    var guideRect: CGRect {
        let aspect: CGFloat = mode == "face" ? 0.84 : 1.586
        let w = min(bounds.width * 0.88,bounds.height * 0.88 * aspect), h = w / aspect
        return CGRect(x: (bounds.width-w)/2, y: (bounds.height-h)/2, width:w, height:h)
    }
    func animateNfc() {
        timer?.invalidate()
        timer = Timer.scheduledTimer(withTimeInterval: 0.04, repeats: true) { [weak self] _ in self?.setNeedsDisplay() }
    }
    func stopAnimation() { timer?.invalidate(); timer = nil }
    override func draw(_ rect: CGRect) {
        UIColor(red:0.45,green:0.88,blue:0.77,alpha:1).setStroke()
        let path: UIBezierPath
        if mode == "nfc" {
            path = UIBezierPath(roundedRect:CGRect(x:bounds.midX-65,y:bounds.midY-120,width:130,height:240),cornerRadius:22)
            let delta = sin(CACurrentMediaTime()*2)*24
            path.append(UIBezierPath(roundedRect:CGRect(x:bounds.midX-90+delta,y:bounds.midY-80,width:135,height:85),cornerRadius:10))
            for radius in [22.0,36.0,50.0] {
                path.append(UIBezierPath(arcCenter:CGPoint(x:bounds.midX,y:bounds.midY-92),radius:radius,startAngle:.pi*1.15,endAngle:.pi*1.85,clockwise:true))
            }
        } else { path = mode == "face" ? UIBezierPath(ovalIn:guideRect) : UIBezierPath(roundedRect:guideRect,cornerRadius:16) }
        outline.path=path.cgPath
    }
    deinit { timer?.invalidate() }
}

struct FrameAnalysis {
    let mrz: MRZAccess?
    let document: CGRect?
    let face: ActiveChallenge.Face?
    let faceRect: CGRect?
    static func analyse(_ image: UIImage, document: Bool) throws -> FrameAnalysis {
        guard let cg = image.cgImage else { throw FlowError.invalidResponse }
        let handler = VNImageRequestHandler(cgImage:cg,options:[:])
        if document {
            let rectangles = VNDetectRectanglesRequest(); rectangles.minimumConfidence = 0.75
            rectangles.minimumAspectRatio = 0.5; rectangles.maximumAspectRatio = 0.8
            let text = VNRecognizeTextRequest(); text.recognitionLevel = .accurate; text.usesLanguageCorrection = false
            try handler.perform([rectangles,text])
            let raw = (text.results ?? []).compactMap { $0.topCandidates(1).first?.string }.joined(separator:"\n")
            return FrameAnalysis(mrz:MRZAccess.parse(raw),document:rectangles.results?.first?.boundingBox,face:nil,faceRect:nil)
        }
        let request = VNDetectFaceLandmarksRequest(); try handler.perform([request])
        guard let faces = request.results, faces.count == 1, let face = faces.first,
              let landmarks = face.landmarks, let yaw = face.yaw, let pitch = face.pitch else {
            return FrameAnalysis(mrz:nil,document:nil,face:nil,faceRect:nil)
        }
        func aspect(_ region: VNFaceLandmarkRegion2D?) -> Double? {
            guard let points = region?.normalizedPoints, points.count >= 4 else { return nil }
            let xs = points.map(\.x), ys = points.map(\.y)
            let width = (xs.max()! - xs.min()!) * face.boundingBox.width * CGFloat(cg.width)
            let height = (ys.max()! - ys.min()!) * face.boundingBox.height * CGFloat(cg.height)
            return width > 0 ? Double(height/width) : nil
        }
        guard let left = aspect(landmarks.leftEye), let right = aspect(landmarks.rightEye), let mouth = aspect(landmarks.innerLips) else {
            return FrameAnalysis(mrz:nil,document:nil,face:nil,faceRect:nil)
        }
        // Vision has no smile classifier. The guided teeth-showing smile is
        // measured as mouth opening, matching the API temporal mouth check.
        func eye(_ value: Double) -> Double { min(1,max(0,(value-0.08)/0.15)) }
        let metrics = ActiveChallenge.Face(yaw:yaw.doubleValue*180 / .pi,pitch:pitch.doubleValue*180 / .pi,
                                           smile:min(1,max(0,mouth/0.4)),leftEye:eye(left),rightEye:eye(right))
        return FrameAnalysis(mrz:nil,document:nil,face:metrics,faceRect:face.boundingBox)
    }
}
