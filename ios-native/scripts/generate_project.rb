#!/usr/bin/env ruby
require 'xcodeproj'
root = File.expand_path('..', __dir__)
project_path = File.join(root, 'InkflowNative.xcodeproj')
project = File.exist?(project_path) ? Xcodeproj::Project.open(project_path) : Xcodeproj::Project.new(project_path)
project.root_object.known_regions = ['en', 'zh-Hans', 'ja', 'ko']
app = project.targets.find { |target| target.name == 'InkflowNative' } || project.new_target(:application, 'InkflowNative', :ios, '17.0')
tests = project.targets.find { |target| target.name == 'InkflowNativeTests' } || project.new_target(:unit_test_bundle, 'InkflowNativeTests', :ios, '17.0')
tests.add_dependency(app) unless tests.dependencies.any? { |dependency| dependency.target == app }
project.add_build_configuration('SharedCore', :debug)
[app, tests].each { |target| target.add_build_configuration('SharedCore', :debug) }
# Reuse file references and target IDs so refreshing membership produces a small diff.
{'InkflowNative' => app, 'InkflowNativeTests' => tests}.each do |folder, target|
  group = project.main_group.groups.find { |item| item.path == folder } || project.main_group.new_group(folder, folder)
  Dir.glob(File.join(root, folder, '*.swift')).sort.each do |path|
    file = group.files.find { |item| item.path == File.basename(path) } || group.new_file(File.basename(path))
    target.source_build_phase.add_file_reference(file) unless target.source_build_phase.files_references.include?(file)
  end
end
[app, tests].each do |target|
  target.build_configurations.each do |config|
    config.build_settings.merge!({
      'PRODUCT_BUNDLE_IDENTIFIER' => "com.inkflow.native#{target == tests ? '.tests' : ''}",
      'SWIFT_VERSION' => '5.0',
      'TARGETED_DEVICE_FAMILY' => '1,2',
      'GENERATE_INFOPLIST_FILE' => 'YES',
      'CODE_SIGN_STYLE' => 'Automatic',
      'IPHONEOS_DEPLOYMENT_TARGET' => '17.0',
      'SWIFT_STRICT_CONCURRENCY' => 'targeted'
    })
    if config.name == 'SharedCore'
      # A framework search path must select the real XCFramework platform slice.
      # Test compilation also imports InkflowNative's InkflowCore module dependency.
      config.build_settings['FRAMEWORK_SEARCH_PATHS'] = ['$(inherited)']
      config.build_settings['FRAMEWORK_SEARCH_PATHS[sdk=iphonesimulator*]'] = ['$(inherited)', '$(SRCROOT)/../android/core/build/XCFrameworks/debug/InkflowCore.xcframework/ios-arm64-simulator']
      config.build_settings['FRAMEWORK_SEARCH_PATHS[sdk=iphoneos*]'] = ['$(inherited)', '$(SRCROOT)/../android/core/build/XCFrameworks/debug/InkflowCore.xcframework/ios-arm64']
    end
    if target == app
      config.build_settings.merge!({
        'INFOPLIST_FILE' => 'InkflowNative/Info.plist',
        'INFOPLIST_KEY_CFBundleDisplayName' => 'Inkflow Native',
        'INFOPLIST_KEY_UILaunchScreen_Generation' => 'YES',
        'INFOPLIST_KEY_UIApplicationSceneManifest_Generation' => 'YES',
        'INFOPLIST_KEY_UISupportedInterfaceOrientations_iPhone' => 'UIInterfaceOrientationPortrait UIInterfaceOrientationLandscapeLeft UIInterfaceOrientationLandscapeRight',
        'INFOPLIST_KEY_UISupportedInterfaceOrientations_iPad' => 'UIInterfaceOrientationPortrait UIInterfaceOrientationPortraitUpsideDown UIInterfaceOrientationLandscapeLeft UIInterfaceOrientationLandscapeRight',
        'ENABLE_USER_SCRIPT_SANDBOXING' => 'YES'
      })
      if config.name == 'SharedCore'
        config.build_settings['SWIFT_ACTIVE_COMPILATION_CONDITIONS'] = '$(inherited) DEBUG INKFLOW_SHARED_CORE'
        config.build_settings['OTHER_LDFLAGS'] = ['$(inherited)', '-framework', 'InkflowCore', '-lc++']
      end
    else
      config.build_settings['TEST_HOST'] = '$(BUILT_PRODUCTS_DIR)/InkflowNative.app/InkflowNative'
      config.build_settings['BUNDLE_LOADER'] = '$(TEST_HOST)'
    end
  end
end
project.save
[['InkflowNative', 'Debug'], ['InkflowNativeSharedCore', 'SharedCore']].each do |name, configuration|
  next if File.exist?(File.join(project_path, 'xcshareddata', 'xcschemes', "#{name}.xcscheme"))
  scheme = Xcodeproj::XCScheme.new
  scheme.add_build_target(app)
  scheme.add_test_target(tests)
  scheme.set_launch_target(app)
  scheme.launch_action.build_configuration = configuration
  scheme.test_action.build_configuration = configuration
  scheme.profile_action.build_configuration = configuration
  scheme.save_as(project_path, name, true)
end
puts "Generated #{project_path}"
