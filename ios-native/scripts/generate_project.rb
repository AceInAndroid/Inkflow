#!/usr/bin/env ruby
require 'xcodeproj'
require 'fileutils'
root = File.expand_path('..', __dir__)
project_path = File.join(root, 'InkflowNative.xcodeproj')
FileUtils.rm_rf(project_path)
project = Xcodeproj::Project.new(project_path)
app = project.new_target(:application, 'InkflowNative', :ios, '17.0')
tests = project.new_target(:unit_test_bundle, 'InkflowNativeTests', :ios, '17.0')
tests.add_dependency(app)
project.add_build_configuration('SharedCore', :debug)
[app, tests].each { |target| target.add_build_configuration('SharedCore', :debug) }
app_group = project.main_group.new_group('InkflowNative', 'InkflowNative')
Dir.glob(File.join(root, 'InkflowNative', '*.swift')).sort.each do |path|
  app.source_build_phase.add_file_reference(app_group.new_file(File.basename(path)))
end
test_group = project.main_group.new_group('InkflowNativeTests', 'InkflowNativeTests')
Dir.glob(File.join(root, 'InkflowNativeTests', '*.swift')).sort.each do |path|
  tests.source_build_phase.add_file_reference(test_group.new_file(File.basename(path)))
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
    if target == app
      config.build_settings.merge!({
        'INFOPLIST_KEY_CFBundleDisplayName' => 'Inkflow Native',
        'INFOPLIST_KEY_UILaunchScreen_Generation' => 'YES',
        'INFOPLIST_KEY_UIApplicationSceneManifest_Generation' => 'YES',
        'INFOPLIST_KEY_UISupportedInterfaceOrientations_iPhone' => 'UIInterfaceOrientationPortrait UIInterfaceOrientationLandscapeLeft UIInterfaceOrientationLandscapeRight',
        'INFOPLIST_KEY_UISupportedInterfaceOrientations_iPad' => 'UIInterfaceOrientationPortrait UIInterfaceOrientationPortraitUpsideDown UIInterfaceOrientationLandscapeLeft UIInterfaceOrientationLandscapeRight',
        'ENABLE_USER_SCRIPT_SANDBOXING' => 'YES'
      })
      if config.name == 'SharedCore'
        config.build_settings['SWIFT_ACTIVE_COMPILATION_CONDITIONS'] = '$(inherited) DEBUG INKFLOW_SHARED_CORE'
        config.build_settings['FRAMEWORK_SEARCH_PATHS'] = ['$(inherited)', '$(SRCROOT)/../android/core/build/XCFrameworks/debug']
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
