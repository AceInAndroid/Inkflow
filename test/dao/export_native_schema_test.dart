import 'dart:convert';
import 'dart:io';

import 'package:anx_reader/config/shared_preference_provider.dart';
import 'package:anx_reader/dao/database.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:sqflite_common_ffi/sqflite_ffi.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  sqfliteFfiInit();

  test('export the current SQLite schema for the native app', () async {
    SharedPreferences.setMockInitialValues({});
    Prefs().prefs = await SharedPreferences.getInstance();
    final db = await databaseFactoryFfi.openDatabase(inMemoryDatabasePath);
    try {
      for (final statement in [
        createBookSQL,
        createThemeSQL,
        createStyleSQL,
        createNoteSQL,
        createReadingTimeSQL,
        createGroupSQL,
        'ALTER TABLE tb_books ADD COLUMN rating REAL',
        'ALTER TABLE tb_books ADD COLUMN group_id INTEGER',
        'ALTER TABLE tb_books ADD COLUMN file_md5 TEXT',
        'ALTER TABLE tb_notes ADD COLUMN reader_note TEXT',
      ]) {
        await db.execute(statement);
      }
      await DBHelper().onUpgradeDatabase(db, 7, currentDbVersion);
      final rows = await db.rawQuery('''
        SELECT sql FROM sqlite_master
        WHERE sql IS NOT NULL AND name NOT LIKE 'sqlite_%'
        ORDER BY CASE type WHEN 'table' THEN 0 ELSE 1 END, name
      ''');
      final statements = rows.map((row) => row['sql'] as String).toList();
      expect(statements.length, greaterThan(30));
      final output = File('android/app/src/main/assets/schema_v22.json');
      final encoded = const JsonEncoder.withIndent('  ').convert(statements);
      if (Platform.environment['EXPORT_NATIVE_SCHEMA'] == '1') {
        await output.parent.create(recursive: true);
        await output.writeAsString(encoded);
      } else {
        expect(await output.readAsString(), encoded);
      }
    } finally {
      await db.close();
    }
  });
}
