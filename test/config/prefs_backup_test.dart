import 'dart:convert';

import 'package:anx_reader/config/shared_preference_provider.dart';
import 'package:anx_reader/enums/ai_prompts.dart';
import 'package:anx_reader/enums/hint_key.dart';
import 'package:anx_reader/enums/sync_protocol.dart';
import 'package:anx_reader/service/translate/index.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  Future<Prefs> initialize(Map<String, Object> values) async {
    SharedPreferences.setMockInitialValues(values);
    // Await initialization so migration and the installation's begin date do
    // not race with restoring the backup into a second preferences store.
    final prefs = Prefs();
    await prefs.initPrefs();
    return prefs;
  }

  test('old typed JSON backups round-trip all supported value types', () async {
    final values = <String, Object>{
      'autoSync': false,
      'maxAiCacheCount': 37,
      'ttsVolume': 0.75,
      'locale': 'zh-CN',
      'enabledAiTools': '["search","dictionary"]',
      'readStyle': '{"fontSize":24}',
      'chapterSplitSelectedRuleId': 'custom-rule',
      'chapterSplitCustomRules': '[]',
      'statisticsDashboardTiles': '["readingTime"]',
      'userPrompts': '[]',
      'readingInfo': '{"headerLeft":"chapterTitle"}',
      // These margins are still consumed when decoding old readingInfo.
      'pageHeaderMargin': 12.0,
      'pageFooterFontSize': 11.0,
      'localMdxDictionaries': <String>['dictionary.mdx'],
      'webdavStatus': true,
      'syncProtocol': 'webdav',
    };
    final prefs = await initialize(values);
    final encoded = await prefs.buildPrefsBackupMap();
    expect(encoded[prefsBackupVersionKey], prefsBackupSchemaVersion);
    expect(encoded['autoSync'], {'type': 'bool', 'value': false});
    expect(encoded['maxAiCacheCount'], {'type': 'int', 'value': 37});
    expect(encoded['ttsVolume'], {'type': 'double', 'value': 0.75});
    expect(encoded['locale'], {'type': 'string', 'value': 'zh-CN'});
    expect(encoded['localMdxDictionaries'], {
      'type': 'stringList',
      'value': ['dictionary.mdx'],
    });

    final decoded = Map<String, dynamic>.from(jsonDecode(jsonEncode(encoded)));
    await initialize({});
    await prefs.applyPrefsBackupMap(decoded);
    for (final entry in values.entries) {
      expect(prefs.prefs.get(entry.key), entry.value, reason: entry.key);
    }

    // Versionless typed backups were also accepted by the old importer.
    await prefs.applyPrefsBackupMap({
      'locale': {'type': 'string', 'value': 'en-US'},
      'ttsRate': {'type': 'double', 'value': 1},
    });
    expect(prefs.prefs.getString('locale'), 'en-US');
    expect(prefs.prefs.getDouble('ttsRate'), 1.0);
  });

  test(
    'dynamic preferences retain current enum keys and custom provider ids',
    () async {
      final values = <String, Object>{
        for (final protocol in SyncProtocol.values)
          '${protocol.name}Info': '{"url":"https://sync.example.test"}',
        for (final prompt in AiPrompts.values)
          'aiPrompt_${prompt.name}': 'custom prompt',
        for (final hint in HintKey.values) 'hint_${hint.code}': false,
        for (final service in TranslateService.values)
          'translateServiceConfig_${service.name}': '{"region":"eastus"}',
        'aiConfig_custom-provider-123': '{"model":"custom-model"}',
        'onlineTtsConfig_custom-service': '{"voice":"custom-voice"}',
        'ttsVoiceModel_custom-service': 'custom-voice',
        'fictionTimelineLastViewedChapter:42': 'chapter-7',
        "englishDictionaryEntry_reader's": '{"word":"reader\'s"}',
        'translationDomCache_42_ai_en_zh_custom-scope': '{"html":"translated"}',
        'translationTextCache_42_ai_en_zh_custom-scope':
            '{"source":"translated"}',
      };
      final prefs = await initialize(values);
      final backup = await prefs.buildPrefsBackupMap();
      for (final key in values.keys) {
        expect(backup, contains(key), reason: key);
      }

      await initialize({});
      await prefs.applyPrefsBackupMap(backup);
      for (final entry in values.entries) {
        expect(prefs.prefs.get(entry.key), entry.value, reason: entry.key);
      }
    },
  );

  test('unknown and removed namespaces neither export nor restore', () async {
    final obsolete = <String, Object>{
      'retiredSyncEnabled': true,
      'retiredSyncEndpoint': 'https://retired.example.test',
      'retiredSyncAccountToken': 'old-private-token',
      'futureUnknownSetting': 'unrecognized',
      'aiPrompt_retiredPrompt': 'unrecognized',
      'hint_retired_hint': false,
      'translateServiceConfig_retiredService': '{}',
      'retiredProtocolInfo': '{}',
      'aiConfig_': '{}',
      'onlineTtsConfig_': '{}',
      'ttsVoiceModel_': 'voice',
      'fictionTimelineLastViewedChapter:invalid': 'chapter-1',
      'translationDomCache_invalid': '{}',
      'englishDictionaryEntry_': '{}',
    };
    final prefs = await initialize({...obsolete, 'autoSync': false});
    final backup = await prefs.buildPrefsBackupMap();
    for (final key in obsolete.keys) {
      expect(backup, isNot(contains(key)), reason: key);
    }
    expect(backup, contains('autoSync'));

    // A legacy backup can contain any old preference. The importer must
    // ignore unknown entries even when they have a valid typed envelope.
    await initialize({'retiredSyncEnabled': false, 'autoSync': true});
    await prefs.applyPrefsBackupMap({
      for (final key in obsolete.keys) key: {'type': 'bool', 'value': true},
      'autoSync': {'type': 'bool', 'value': false},
    });
    expect(prefs.prefs.getBool('retiredSyncEnabled'), isFalse);
    for (final key in obsolete.keys.where(
      (key) => key != 'retiredSyncEnabled',
    )) {
      expect(prefs.prefs.containsKey(key), isFalse, reason: key);
    }
    expect(prefs.autoSync, isFalse);
  });

  test(
    'device state and credentials stay local in both backup directions',
    () async {
      final local = <String, Object>{
        'readingAgentSyncDeviceId': 'this-installation',
        'selfHostedProgressSyncEndpoint': 'https://progress.example.test',
        'selfHostedProgressSyncEnabled': true,
        'selfHostedProgressSyncToken': 'local-session',
        'selfHostedProgressSyncTokenExpiresAt': 123,
        'selfHostedProgressSyncCursor': '42',
        'readingAgentLocalBackfillStarts': '{"42":0.5}',
        'aiTokenUsageMonthly': '{}',
        'readingExperienceDiagnostics.v1': '{}',
        'aiExtractionConfig': '{}',
        'iapPurchaseStatus': true,
        'iapLastCheckTime': '2026-11-27',
        'deviceDisplayProfile': 'standard',
        'themeMode': 'dark',
        'themeColor': 0xFF123456,
        'trueDarkMode': true,
      };
      final prefs = await initialize(local);
      final backup = await prefs.buildPrefsBackupMap();
      for (final key in local.keys) {
        expect(backup, isNot(contains(key)), reason: key);
      }
      await prefs.applyPrefsBackupMap({
        for (final key in local.keys)
          key: {'type': 'string', 'value': 'replacement'},
      });
      for (final entry in local.entries) {
        expect(prefs.prefs.get(entry.key), entry.value, reason: entry.key);
      }
    },
  );

  test(
    'malformed entries do not interrupt valid preference restoration',
    () async {
      final prefs = await initialize({
        'localMdxDictionaries': <String>['keep-local.mdx'],
        'locale': 'zh-CN',
        'ttsRate': 0.5,
        'maxAiCacheCount': 10,
        'autoSync': true,
      });
      await prefs.applyPrefsBackupMap({
        prefsBackupVersionKey: prefsBackupSchemaVersion,
        'localMdxDictionaries': {
          'type': 'stringList',
          'value': ['valid', 42],
        },
        'locale': {'type': 'string', 'value': null},
        'ttsRate': {'type': 'double', 'value': 'invalid'},
        'maxAiCacheCount': {'type': 'futureType', 'value': 100},
        'userPrompts': 'missing envelope',
        'customCSS': {'value': 'missing type'},
        'autoSync': {'type': 'bool', 'value': false},
      });
      expect(prefs.prefs.getStringList('localMdxDictionaries'), [
        'keep-local.mdx',
      ]);
      expect(prefs.prefs.getString('locale'), 'zh-CN');
      expect(prefs.prefs.getDouble('ttsRate'), 0.5);
      expect(prefs.prefs.getInt('maxAiCacheCount'), 10);
      expect(prefs.prefs.containsKey('userPrompts'), isFalse);
      expect(prefs.prefs.containsKey('customCSS'), isFalse);
      expect(prefs.autoSync, isFalse);
    },
  );
}
