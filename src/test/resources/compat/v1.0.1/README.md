# Bokfri v1.0.1 compatibility fixtures

These fixtures were created from the released `v1.0.1` tag at commit
`6e23312fd5e920007c9fd10dad3b0056bc576fc2` using Java 21.

- `database-v1.0.1.zip` contains the cleanly closed `db/JFSDB.*` files shipped
  by v1.0.1.
- `backup-v1.0.1.zip` was created from those database files by v1.0.1's
  `SSBackupFactory.createBackup` implementation and contains its serialized
  legacy `backup.info` entry.

The database contains only the public example company bundled with Bokfri and
no real personal or business data. Tests must extract the archives to temporary
directories and must never modify these source fixtures.
