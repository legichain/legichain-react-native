module.exports = {
  dependency: {
    platforms: {
      android: {
        sourceDir: './android',
        packageImportPath: 'import com.legichain.react.LegichainKycPackage;',
        packageInstance: 'new LegichainKycPackage()',
      },
      ios: { podspecPath: './LegichainReactNative.podspec' },
    },
  },
};
