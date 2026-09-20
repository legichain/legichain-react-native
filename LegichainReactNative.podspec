require 'json'
package = JSON.parse(File.read(File.join(__dir__, 'package.json')))
Pod::Spec.new do |s|
  s.name = 'LegichainReactNative'
  s.version = package['version']
  s.summary = 'Native Legichain KYC capture, NFC and guided liveness for React Native.'
  s.homepage = 'https://legichain.com'
  s.license = { :type => 'MIT', :file => 'LICENSE' }
  s.author = { 'Legichain' => 'contact@legichain.com' }
  s.source = { :git => 'https://github.com/legichain/legichain-react-native.git', :tag => "v#{s.version}" }
  s.platforms = { :ios => '15.0' }
  s.swift_version = '5.9'
  s.source_files = 'ios/**/*.{swift,h,m}'
  s.dependency 'React-Core'
  s.dependency 'NFCPassportReader', '2.3.1'
  s.frameworks = 'AVFoundation', 'CoreNFC', 'Vision', 'UIKit'
end
