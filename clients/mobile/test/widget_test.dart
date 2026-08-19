import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:lifeos_mobile/main.dart';

void main() {
  testWidgets('mobile shell exposes shared primary destinations',
      (tester) async {
    await tester.pumpWidget(
      MaterialApp(
        home: LifeOsShell(accessToken: 'test-token', onSignOut: () {}),
      ),
    );

    expect(find.text('Home'), findsWidgets);
    expect(find.text('Plan'), findsOneWidget);
    expect(find.text('Calendar'), findsOneWidget);
    expect(find.text('Money'), findsOneWidget);
    expect(find.text('Vault'), findsOneWidget);
    expect(find.text('Assistant'), findsOneWidget);
    expect(find.text('Sessions'), findsOneWidget);
    expect(find.text('Settings'), findsOneWidget);

    await tester.tap(find.text('Plan').last);
    await tester.pump();
    expect(find.text('Tasks, goals, habits, routines, and milestones.'),
        findsOneWidget);
    await tester.pump(const Duration(milliseconds: 100));
    expect(find.textContaining('not connected'), findsNothing);
    expect(find.byTooltip('Sign out'), findsOneWidget);
  });

  testWidgets('entry point starts behind the memory-only auth boundary',
      (tester) async {
    await tester.pumpWidget(
      MaterialApp(
        home: AuthPage(onAuthenticated: (_) async {}),
      ),
    );

    expect(find.text('Sign in to LifeOS'), findsOneWidget);
    expect(
        find.textContaining('never written to device storage'), findsOneWidget);
  });
}
