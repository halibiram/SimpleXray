#!/usr/bin/env python3
"""
SimpleXray Log Analyzer
------------------------
Otomatik log analiz aracı. Logcat çıktılarını analiz eder ve şu pattern'leri tespit eder:
- "connect → disconnect" pattern'leri
- Fatal signals (SIGSEGV, SIGABRT, etc.)
- Exit codes
- Permission denied hataları
- VPN unexpected stops
- Crash patterns

Kullanım:
  python log-analyzer.py <logfile>
  python log-analyzer.py <logfile> --json
  python log-analyzer.py <logfile> --summary
"""

import argparse
import re
import sys
from collections import defaultdict
from datetime import datetime
from typing import List, Dict, Tuple, Optional
import json

# Pattern definitions
PATTERNS = {
    'connect': [
        r'ACTION_CONNECT',
        r'startXray',
        r'VPN.*start',
        r'TProxyService.*start',
        r'Connecting',
    ],
    'disconnect': [
        r'ACTION_DISCONNECT',
        r'stopXray',
        r'VPN.*stop',
        r'TProxyService.*stop',
        r'Disconnecting',
        r'onDestroy',
    ],
    'fatal_signal': [
        r'F/libc\s+.*Fatal signal',
        r'F/DEBUG\s+.*signal',
        r'SIGSEGV',
        r'SIGABRT',
        r'SIGBUS',
        r'SIGFPE',
        r'SIGILL',
        r'fatal signal \d+',
    ],
    'exit_code': [
        r'exit code',
        r'exitcode',
        r'exit.*code.*\d+',
        r'process.*exit',
        r'Process.*died',
    ],
    'permission_denied': [
        r'Permission denied',
        r'EACCES',
        r'permission.*denied',
        r'access.*denied',
    ],
    'vpn_unexpected_stop': [
        r'Unexpected Stop',
        r'VPN.*unexpected',
        r'Unexpected.*VPN',
    ],
    'crash': [
        r'AndroidRuntime.*FATAL',
        r'FATAL EXCEPTION',
        r'crash',
        r'Exception.*RuntimeException',
        r'NullPointerException',
        r'IllegalStateException',
    ],
    'connection_error': [
        r'connection.*failed',
        r'failed.*connect',
        r'ECONNREFUSED',
        r'ETIMEDOUT',
        r'ENETUNREACH',
    ],
}

class LogAnalyzer:
    def __init__(self, log_file: str):
        self.log_file = log_file
        self.matches: Dict[str, List[Dict]] = defaultdict(list)
        self.connect_disconnect_pairs: List[Tuple[Dict, Optional[Dict]]] = []
        self.stats = {
            'total_lines': 0,
            'connect_count': 0,
            'disconnect_count': 0,
            'fatal_signals': 0,
            'exit_codes': 0,
            'permission_denied': 0,
            'vpn_unexpected_stops': 0,
            'crashes': 0,
            'connection_errors': 0,
        }
        
    def parse_line(self, line: str) -> Optional[Dict]:
        """Parse a logcat line and extract timestamp, level, tag, and message."""
        # Logcat time format: MM-DD HH:MM:SS.mmm
        time_pattern = r'(\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}\.\d{3})'
        match = re.match(time_pattern, line)
        if not match:
            return None
            
        timestamp_str = match.group(1)
        rest = line[match.end():].strip()
        
        # Extract level and tag: "D/TProxyService: message"
        level_tag_match = re.match(r'([VDIWEF])/([^:]+):\s*(.*)', rest)
        if level_tag_match:
            level = level_tag_match.group(1)
            tag = level_tag_match.group(2)
            message = level_tag_match.group(3)
        else:
            level = 'V'
            tag = 'Unknown'
            message = rest
            
        return {
            'timestamp': timestamp_str,
            'level': level,
            'tag': tag,
            'message': message,
            'raw': line,
        }
    
    def analyze(self):
        """Analyze the log file."""
        try:
            with open(self.log_file, 'r', encoding='utf-8', errors='ignore') as f:
                for line_num, line in enumerate(f, 1):
                    self.stats['total_lines'] += 1
                    parsed = self.parse_line(line.strip())
                    if not parsed:
                        continue
                    
                    # Check all patterns
                    for category, patterns in PATTERNS.items():
                        for pattern in patterns:
                            if re.search(pattern, parsed['message'], re.IGNORECASE):
                                self.matches[category].append({
                                    'line': line_num,
                                    'timestamp': parsed['timestamp'],
                                    'level': parsed['level'],
                                    'tag': parsed['tag'],
                                    'message': parsed['message'],
                                })
                                if category == 'connect':
                                    self.stats['connect_count'] += 1
                                elif category == 'disconnect':
                                    self.stats['disconnect_count'] += 1
                                elif category == 'fatal_signal':
                                    self.stats['fatal_signals'] += 1
                                elif category == 'exit_code':
                                    self.stats['exit_codes'] += 1
                                elif category == 'permission_denied':
                                    self.stats['permission_denied'] += 1
                                elif category == 'vpn_unexpected_stop':
                                    self.stats['vpn_unexpected_stops'] += 1
                                elif category == 'crash':
                                    self.stats['crashes'] += 1
                                elif category == 'connection_error':
                                    self.stats['connection_errors'] += 1
                                break  # Only count once per line
                    
        except FileNotFoundError:
            print(f"ERROR: File not found: {self.log_file}", file=sys.stderr)
            sys.exit(1)
        except Exception as e:
            print(f"ERROR: Failed to analyze log: {e}", file=sys.stderr)
            sys.exit(1)
    
    def find_connect_disconnect_pairs(self):
        """Find connect → disconnect patterns."""
        connects = self.matches.get('connect', [])
        disconnects = self.matches.get('disconnect', [])
        
        for connect in connects:
            # Find the next disconnect after this connect
            next_disconnect = None
            for disconnect in disconnects:
                if disconnect['line'] > connect['line']:
                    next_disconnect = disconnect
                    break
            
            self.connect_disconnect_pairs.append((connect, next_disconnect))
    
    def print_summary(self):
        """Print a summary of findings."""
        print("=" * 70)
        print("SimpleXray Log Analysis Summary")
        print("=" * 70)
        print(f"Total lines analyzed: {self.stats['total_lines']}")
        print()
        
        print("Event Counts:")
        print(f"  Connects: {self.stats['connect_count']}")
        print(f"  Disconnects: {self.stats['disconnect_count']}")
        print(f"  Fatal Signals: {self.stats['fatal_signals']}")
        print(f"  Exit Codes: {self.stats['exit_codes']}")
        print(f"  Permission Denied: {self.stats['permission_denied']}")
        print(f"  VPN Unexpected Stops: {self.stats['vpn_unexpected_stops']}")
        print(f"  Crashes: {self.stats['crashes']}")
        print(f"  Connection Errors: {self.stats['connection_errors']}")
        print()
        
        # Connect-Disconnect pairs
        self.find_connect_disconnect_pairs()
        if self.connect_disconnect_pairs:
            print(f"Connect → Disconnect Patterns: {len(self.connect_disconnect_pairs)}")
            unmatched_connects = sum(1 for _, d in self.connect_disconnect_pairs if d is None)
            if unmatched_connects > 0:
                print(f"  ⚠️  Unmatched connects (no disconnect found): {unmatched_connects}")
            print()
        
        # Critical issues
        critical = []
        if self.stats['fatal_signals'] > 0:
            critical.append(f"Fatal signals detected: {self.stats['fatal_signals']}")
        if self.stats['vpn_unexpected_stops'] > 0:
            critical.append(f"VPN unexpected stops: {self.stats['vpn_unexpected_stops']}")
        if self.stats['crashes'] > 0:
            critical.append(f"Crashes detected: {self.stats['crashes']}")
        
        if critical:
            print("⚠️  Critical Issues:")
            for issue in critical:
                print(f"  - {issue}")
            print()
    
    def print_detailed(self):
        """Print detailed findings."""
        self.print_summary()
        
        # Print fatal signals
        if self.matches.get('fatal_signal'):
            print("=" * 70)
            print("Fatal Signals:")
            print("=" * 70)
            for match in self.matches['fatal_signal']:
                print(f"Line {match['line']} [{match['timestamp']}] {match['level']}/{match['tag']}: {match['message']}")
            print()
        
        # Print VPN unexpected stops
        if self.matches.get('vpn_unexpected_stop'):
            print("=" * 70)
            print("VPN Unexpected Stops:")
            print("=" * 70)
            for match in self.matches['vpn_unexpected_stop']:
                print(f"Line {match['line']} [{match['timestamp']}] {match['level']}/{match['tag']}: {match['message']}")
            print()
        
        # Print crashes
        if self.matches.get('crash'):
            print("=" * 70)
            print("Crashes:")
            print("=" * 70)
            for match in self.matches['crash']:
                print(f"Line {match['line']} [{match['timestamp']}] {match['level']}/{match['tag']}: {match['message']}")
            print()
        
        # Print permission denied
        if self.matches.get('permission_denied'):
            print("=" * 70)
            print("Permission Denied Errors:")
            print("=" * 70)
            for match in self.matches['permission_denied']:
                print(f"Line {match['line']} [{match['timestamp']}] {match['level']}/{match['tag']}: {match['message']}")
            print()
        
        # Print connect-disconnect pairs
        self.find_connect_disconnect_pairs()
        if self.connect_disconnect_pairs:
            print("=" * 70)
            print("Connect → Disconnect Patterns:")
            print("=" * 70)
            for i, (connect, disconnect) in enumerate(self.connect_disconnect_pairs, 1):
                print(f"Pair {i}:")
                print(f"  Connect:  Line {connect['line']} [{connect['timestamp']}] {connect['message']}")
                if disconnect:
                    print(f"  Disconnect: Line {disconnect['line']} [{disconnect['timestamp']}] {disconnect['message']}")
                else:
                    print(f"  Disconnect: NOT FOUND (unmatched connect)")
                print()
    
    def to_json(self) -> Dict:
        """Export results as JSON."""
        self.find_connect_disconnect_pairs()
        return {
            'stats': self.stats,
            'matches': {
                category: matches for category, matches in self.matches.items()
            },
            'connect_disconnect_pairs': [
                {
                    'connect': connect,
                    'disconnect': disconnect,
                }
                for connect, disconnect in self.connect_disconnect_pairs
            ],
        }


def main():
    parser = argparse.ArgumentParser(description="SimpleXray Log Analyzer")
    parser.add_argument('logfile', help='Log file to analyze')
    parser.add_argument('--json', action='store_true', help='Output as JSON')
    parser.add_argument('--summary', action='store_true', help='Show only summary')
    
    args = parser.parse_args()
    
    analyzer = LogAnalyzer(args.logfile)
    analyzer.analyze()
    
    if args.json:
        print(json.dumps(analyzer.to_json(), indent=2))
    elif args.summary:
        analyzer.print_summary()
    else:
        analyzer.print_detailed()


if __name__ == '__main__':
    main()

