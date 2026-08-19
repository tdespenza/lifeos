import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:http/http.dart' as http;

const _apiBaseUrl = String.fromEnvironment(
  'LIFEOS_API_BASE_URL',
  defaultValue: 'http://localhost:8080',
);

Uri _apiUri(String path) => Uri.parse('$_apiBaseUrl$path');

void main() => runApp(const LifeOsApp());

class LifeOsApp extends StatelessWidget {
  const LifeOsApp({super.key});

  @override
  Widget build(BuildContext context) => MaterialApp(
        title: 'LifeOS',
        theme: ThemeData(
          colorScheme: ColorScheme.fromSeed(seedColor: const Color(0xff1f5c7a)),
          useMaterial3: true,
        ),
        home: const LifeOsEntryPoint(),
      );
}

class LifeOsEntryPoint extends StatefulWidget {
  const LifeOsEntryPoint({super.key});

  @override
  State<LifeOsEntryPoint> createState() => _LifeOsEntryPointState();
}

class _LifeOsEntryPointState extends State<LifeOsEntryPoint> {
  static const _accessTokenKey = 'lifeos.access_token';
  static const _secureStorage = FlutterSecureStorage();
  String? _accessToken;
  bool _restoring = true;

  @override
  void initState() {
    super.initState();
    _restoreAccessToken();
  }

  Future<void> _restoreAccessToken() async {
    try {
      final token = await _secureStorage.read(key: _accessTokenKey);
      if (mounted) setState(() => _accessToken = token);
    } catch (_) {
      // Missing platform keystore support is treated as signed out, never as a reason to expose
      // an unprotected session or log the token.
    } finally {
      if (mounted) setState(() => _restoring = false);
    }
  }

  Future<void> _setAccessToken(String token) async {
    await _secureStorage.write(key: _accessTokenKey, value: token);
    if (mounted) setState(() => _accessToken = token);
  }

  Future<void> _signOut() async {
    await _secureStorage.delete(key: _accessTokenKey);
    if (mounted) setState(() => _accessToken = null);
  }

  @override
  Widget build(BuildContext context) => _restoring
      ? const Scaffold(
          body: Center(
            child: CircularProgressIndicator(
              semanticsLabel: 'Restoring secure session',
            ),
          ),
        )
      : _accessToken == null
          ? AuthPage(onAuthenticated: _setAccessToken)
          : LifeOsShell(
              accessToken: _accessToken!,
              onSignOut: _signOut,
            );
}

class AuthPage extends StatefulWidget {
  const AuthPage({required this.onAuthenticated, super.key});

  final Future<void> Function(String token) onAuthenticated;

  @override
  State<AuthPage> createState() => _AuthPageState();
}

class _AuthPageState extends State<AuthPage> {
  final _email = TextEditingController();
  final _displayName = TextEditingController();
  final _password = TextEditingController();
  bool _register = false;
  bool _busy = false;
  String? _error;

  @override
  void dispose() {
    _email.dispose();
    _displayName.dispose();
    _password.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    setState(() {
      _busy = true;
      _error = null;
    });
    final endpoint =
        _apiUri(_register ? '/api/v1/accounts' : '/api/v1/auth/login');
    final body = _register
        ? {
            'email': _email.text,
            'displayName': _displayName.text,
            'password': _password.text
          }
        : {'email': _email.text, 'password': _password.text};
    final headers = <String, String>{
      'Accept': 'application/json',
      'Content-Type': 'application/json'
    };
    if (_register)
      headers['Idempotency-Key'] =
          DateTime.now().toUtc().microsecondsSinceEpoch.toString();
    try {
      final registrationResponse = await http
          .post(endpoint, headers: headers, body: jsonEncode(body))
          .timeout(const Duration(seconds: 8));
      if (registrationResponse.statusCode < 200 ||
          registrationResponse.statusCode >= 300) {
        throw StateError('authentication failed');
      }
      // Registration returns the account resource by design. Exchange the credentials separately
      // so account creation never silently creates a long-lived client session.
      final response = _register
          ? await http
              .post(
                _apiUri('/api/v1/auth/login'),
                headers: {
                  'Accept': 'application/json',
                  'Content-Type': 'application/json',
                },
                body: jsonEncode({
                  'email': _email.text,
                  'password': _password.text,
                }),
              )
              .timeout(const Duration(seconds: 8))
          : registrationResponse;
      if (response.statusCode < 200 || response.statusCode >= 300) {
        throw StateError('authentication failed');
      }
      final result = jsonDecode(response.body) as Map<String, dynamic>;
      final token = result['accessToken'] as String?;
      if (token == null || token.isEmpty)
        throw StateError('authentication response incomplete');
      _password.clear();
      await widget.onAuthenticated(token);
    } catch (_) {
      setState(() => _error =
          'We could not complete that request. Check your details and try again.');
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) => Scaffold(
        appBar: AppBar(title: const Text('LifeOS')),
        body: Center(
          child: SingleChildScrollView(
            padding: const EdgeInsets.all(24),
            child: ConstrainedBox(
              constraints: const BoxConstraints(maxWidth: 440),
              child: Card(
                child: Padding(
                  padding: const EdgeInsets.all(24),
                  child: AutofillGroup(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.stretch,
                      children: [
                        Text(
                            _register
                                ? 'Create your LifeOS account'
                                : 'Sign in to LifeOS',
                            style: Theme.of(context).textTheme.headlineSmall),
                        const SizedBox(height: 12),
                        const Text(
                            'Your access token stays in memory for this session and is never written to device storage.'),
                        const SizedBox(height: 20),
                        TextField(
                            controller: _email,
                            keyboardType: TextInputType.emailAddress,
                            autofillHints: const [AutofillHints.username],
                            decoration: const InputDecoration(
                                labelText: 'Email',
                                border: OutlineInputBorder())),
                        if (_register) ...[
                          const SizedBox(height: 12),
                          TextField(
                              controller: _displayName,
                              autofillHints: const [AutofillHints.name],
                              decoration: const InputDecoration(
                                  labelText: 'Display name',
                                  border: OutlineInputBorder())),
                        ],
                        const SizedBox(height: 12),
                        TextField(
                            controller: _password,
                            obscureText: true,
                            autofillHints: const [AutofillHints.password],
                            decoration: const InputDecoration(
                                labelText: 'Password',
                                border: OutlineInputBorder())),
                        if (_error != null) ...[
                          const SizedBox(height: 12),
                          Text(_error!,
                              style: TextStyle(
                                  color: Theme.of(context).colorScheme.error),
                              semanticsLabel: _error!),
                        ],
                        const SizedBox(height: 20),
                        FilledButton(
                            onPressed: _busy ? null : _submit,
                            child: Text(_busy
                                ? 'Working…'
                                : (_register ? 'Create account' : 'Sign in'))),
                        TextButton(
                            onPressed: _busy
                                ? null
                                : () => setState(() => _register = !_register),
                            child: Text(_register
                                ? 'I already have an account'
                                : 'Create an account instead')),
                      ],
                    ),
                  ),
                ),
              ),
            ),
          ),
        ),
      );
}

class LifeOsShell extends StatefulWidget {
  const LifeOsShell(
      {required this.accessToken, required this.onSignOut, super.key});

  final String accessToken;
  final VoidCallback onSignOut;

  @override
  State<LifeOsShell> createState() => _LifeOsShellState();
}

class _LifeOsShellState extends State<LifeOsShell> {
  static const _destinations =
      <({String label, IconData icon, String description})>[
    (
      label: 'Home',
      icon: Icons.home_outlined,
      description: 'Priorities, reminders, and measured signals.',
    ),
    (
      label: 'Plan',
      icon: Icons.check_circle_outline,
      description: 'Tasks, goals, habits, routines, and milestones.',
    ),
    (
      label: 'Calendar',
      icon: Icons.calendar_today_outlined,
      description: 'Events, focus blocks, conflicts, and reminders.',
    ),
    (
      label: 'Money',
      icon: Icons.account_balance_wallet_outlined,
      description: 'Budgets, transactions, insights, and forecasts.',
    ),
    (
      label: 'Vault',
      icon: Icons.folder_outlined,
      description: 'Private documents, search, and proof status.',
    ),
    (
      label: 'Assistant',
      icon: Icons.auto_awesome_outlined,
      description: 'Grounded answers and confirmed actions.',
    ),
    (
      label: 'Sessions',
      icon: Icons.video_call_outlined,
      description: 'Scheduled sessions, timers, and recordings.',
    ),
    (
      label: 'Settings',
      icon: Icons.settings_outlined,
      description: 'Profile, privacy, AI preferences, and devices.',
    ),
  ];

  int _selectedIndex = 0;

  @override
  Widget build(BuildContext context) {
    final destination = _destinations[_selectedIndex];
    return Scaffold(
      appBar: AppBar(
        title: Text(destination.label),
        actions: [
          const Padding(
            padding: EdgeInsets.symmetric(horizontal: 8),
            child: Center(child: Text('Private workspace')),
          ),
          IconButton(
            tooltip: 'Sign out',
            onPressed: widget.onSignOut,
            icon: const Icon(Icons.logout),
          ),
        ],
      ),
      body: Semantics(
        liveRegion: true,
        label: destination.description,
        child: _selectedIndex == 0
            ? DashboardPage(accessToken: widget.accessToken)
            : DestinationPage(
                destination: destination,
                accessToken: widget.accessToken,
              ),
      ),
      bottomNavigationBar: NavigationBar(
        selectedIndex: _selectedIndex,
        onDestinationSelected: (index) =>
            setState(() => _selectedIndex = index),
        destinations: [
          for (final item in _destinations)
            NavigationDestination(icon: Icon(item.icon), label: item.label),
        ],
      ),
    );
  }
}

class DestinationPage extends StatefulWidget {
  const DestinationPage(
      {required this.destination, required this.accessToken, super.key});

  final ({String label, IconData icon, String description}) destination;
  final String accessToken;

  @override
  State<DestinationPage> createState() => _DestinationPageState();
}

class _DestinationPageState extends State<DestinationPage> {
  late Future<List<Map<String, dynamic>>> _items;

  @override
  void initState() {
    super.initState();
    _items = _load();
  }

  Future<List<Map<String, dynamic>>> _load() async {
    final path = switch (widget.destination.label) {
      'Plan' => '/api/v1/tasks?limit=30',
      'Calendar' => '/api/v1/calendar/events?limit=30',
      'Money' => '/api/v1/finance/transactions?page=0&pageSize=30',
      'Vault' => '/api/v1/documents/search?q=lifeos&pageSize=20',
      'Sessions' => '/api/v1/media/sessions?limit=30',
      _ => null,
    };
    if (path == null) {
      return [
        {
          'kind': widget.destination.label,
          'title': 'Ready for your workspace',
          'detail':
              'Use the bounded ${widget.destination.label.toLowerCase()} API when a resource is selected.',
          'status': 'Ready',
        }
      ];
    }
    final response = await http.get(
      _apiUri(path),
      headers: {
        'Accept': 'application/json',
        'Authorization': 'Bearer ${widget.accessToken}',
      },
    ).timeout(const Duration(seconds: 5));
    if (response.statusCode < 200 || response.statusCode >= 300) {
      throw StateError('destination unavailable');
    }
    final decoded = jsonDecode(response.body);
    final list = decoded is List
        ? decoded
        : decoded is Map<String, dynamic>
            ? (decoded['items'] ??
                decoded['content'] ??
                decoded['events'] ??
                decoded['documents'] ??
                decoded['sessions'] ??
                const [])
            : const [];
    if (list is! List) return const [];
    return list.whereType<Map<String, dynamic>>().toList(growable: false);
  }

  String _title(Map<String, dynamic> item) => (item['title'] ??
          item['name'] ??
          item['category'] ??
          item['subject'] ??
          widget.destination.label)
      .toString();

  String _detail(Map<String, dynamic> item) {
    final values = [
      item['startAt'],
      item['occurredOn'],
      item['dueAt'],
      item['currency'],
      item['amountMinor'],
      item['description']
    ]
        .where((value) => value != null && value.toString().isNotEmpty)
        .map((value) => value.toString())
        .take(2)
        .join(' · ');
    return values.isEmpty ? 'Owner-scoped resource' : values;
  }

  Future<void> _confirmSessionAction() async {
    final sessionController = TextEditingController();
    final versionController = TextEditingController(text: '0');
    final actionController = TextEditingController();
    final values = await showDialog<Map<String, String>>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: const Text('Confirm follow-up task'),
        content: SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              TextField(
                  controller: sessionController,
                  decoration: const InputDecoration(labelText: 'Session ID')),
              TextField(
                  controller: versionController,
                  keyboardType: TextInputType.number,
                  decoration:
                      const InputDecoration(labelText: 'Artifact version')),
              TextField(
                  controller: actionController,
                  maxLength: 255,
                  decoration:
                      const InputDecoration(labelText: 'Exact action item')),
            ],
          ),
        ),
        actions: [
          TextButton(
              onPressed: () => Navigator.pop(dialogContext),
              child: const Text('Cancel')),
          FilledButton(
              onPressed: () => Navigator.pop(dialogContext, {
                    'session': sessionController.text.trim(),
                    'version': versionController.text.trim(),
                    'action': actionController.text.trim(),
                  }),
              child: const Text('Create task')),
        ],
      ),
    );
    sessionController.dispose();
    versionController.dispose();
    actionController.dispose();
    if (values == null ||
        values['session']!.isEmpty ||
        values['action']!.isEmpty) return;
    final version = int.tryParse(values['version']!);
    if (version == null || version < 0) return;
    try {
      final response = await http
          .post(
            _apiUri(
                '/api/v1/media/sessions/${Uri.encodeComponent(values['session']!)}/post-session/tasks'),
            headers: {
              'Accept': 'application/json',
              'Content-Type': 'application/json',
              'Authorization': 'Bearer ${widget.accessToken}',
              'Idempotency-Key':
                  'mobile-${DateTime.now().toUtc().microsecondsSinceEpoch}',
              'If-Match': '"$version"',
            },
            body: jsonEncode(
                {'actionItem': values['action'], 'priority': 3, 'dueAt': null}),
          )
          .timeout(const Duration(seconds: 8));
      if (response.statusCode < 200 || response.statusCode >= 300)
        throw StateError('confirmation unavailable');
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
            const SnackBar(content: Text('Follow-up task created.')));
        setState(() => _items = _load());
      }
    } catch (_) {
      if (mounted)
        ScaffoldMessenger.of(context).showSnackBar(const SnackBar(
            content: Text('The action item could not be confirmed.')));
    }
  }

  @override
  Widget build(BuildContext context) => Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 20, 16, 4),
            child: Text(widget.destination.description),
          ),
          if (widget.destination.label == 'Sessions')
            Padding(
              padding: const EdgeInsets.fromLTRB(16, 8, 16, 4),
              child: OutlinedButton.icon(
                onPressed: _confirmSessionAction,
                icon: const Icon(Icons.task_alt),
                label: const Text('Confirm an action item as a task'),
              ),
            ),
          Expanded(
            child: FutureBuilder<List<Map<String, dynamic>>>(
              future: _items,
              builder: (context, snapshot) {
                if (snapshot.hasError) {
                  return Center(
                    child: Column(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        Text(
                            '${widget.destination.label} is temporarily unavailable.'),
                        const SizedBox(height: 12),
                        FilledButton(
                            onPressed: () => setState(() => _items = _load()),
                            child: const Text('Try again')),
                      ],
                    ),
                  );
                }
                if (!snapshot.hasData) {
                  return const Center(
                      child: CircularProgressIndicator(
                          semanticsLabel: 'Loading destination'));
                }
                final items = snapshot.data!;
                if (items.isEmpty) {
                  return Center(
                      child: Text(
                          'No ${widget.destination.label.toLowerCase()} resources yet.'));
                }
                return RefreshIndicator(
                  onRefresh: () async => setState(() => _items = _load()),
                  child: ListView.builder(
                    padding: const EdgeInsets.all(16),
                    itemCount: items.length,
                    itemBuilder: (context, index) {
                      final item = items[index];
                      return Card(
                        child: ListTile(
                          title: Text(_title(item)),
                          subtitle: Text(_detail(item)),
                          trailing: item['status'] == null
                              ? null
                              : Text(item['status'].toString()),
                        ),
                      );
                    },
                  ),
                );
              },
            ),
          ),
        ],
      );
}

class DashboardPage extends StatefulWidget {
  const DashboardPage({required this.accessToken, super.key});

  final String accessToken;

  @override
  State<DashboardPage> createState() => _DashboardPageState();
}

class _DashboardPageState extends State<DashboardPage> {
  late Future<List<Map<String, dynamic>>> _dashboard;

  @override
  void initState() {
    super.initState();
    _dashboard = _load();
  }

  Future<List<Map<String, dynamic>>> _load() async {
    final response = await http.get(
      _apiUri('/api/v1/analytics/dashboard?periodDays=30'),
      headers: {
        'Accept': 'application/json',
        'Authorization': 'Bearer ${widget.accessToken}',
      },
    ).timeout(const Duration(seconds: 5));
    if (response.statusCode < 200 || response.statusCode >= 300) {
      throw StateError('dashboard unavailable');
    }
    final body = jsonDecode(response.body) as Map<String, dynamic>;
    return (body['metrics'] as List<dynamic>).cast<Map<String, dynamic>>();
  }

  @override
  Widget build(BuildContext context) =>
      FutureBuilder<List<Map<String, dynamic>>>(
        future: _dashboard,
        builder: (context, snapshot) {
          if (snapshot.hasError) {
            return Center(
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  const Text(
                    'Dashboard is temporarily unavailable.',
                    semanticsLabel: 'Dashboard is temporarily unavailable.',
                  ),
                  const SizedBox(height: 12),
                  FilledButton(
                    onPressed: () => setState(() => _dashboard = _load()),
                    child: const Text('Try again'),
                  ),
                ],
              ),
            );
          }
          if (!snapshot.hasData)
            return const Center(
              child: CircularProgressIndicator(
                semanticsLabel: 'Loading dashboard',
              ),
            );
          if (snapshot.data!.isEmpty)
            return const Center(
              child: Text(
                'No measured signals yet. Start with a task or calendar event.',
              ),
            );
          return RefreshIndicator(
            onRefresh: () async => setState(() => _dashboard = _load()),
            child: ListView.builder(
              padding: const EdgeInsets.all(16),
              itemCount: snapshot.data!.length,
              itemBuilder: (context, index) {
                final metric = snapshot.data![index];
                return Card(
                  child: ListTile(
                    title: Text(metric['key'] as String),
                    subtitle: const Text('30-day measured signal'),
                    trailing: Text('${metric['value']}'),
                  ),
                );
              },
            ),
          );
        },
      );
}
