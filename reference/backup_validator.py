"""Independent strict oracle for backup v1. Standard library only; not the Android importer."""
from __future__ import annotations
import json, re, uuid
from datetime import date, datetime
from typing import Any

MAX_BYTES = 20 * 1024 * 1024
class BackupError(ValueError): pass

def fail(message: str) -> None:
    raise BackupError(message)

def _pairs(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result: fail('duplicate JSON key')
        result[key] = value
    return result

def _keys(value: Any, expected: set[str]) -> None:
    if type(value) is not dict or set(value) != expected: fail('unexpected or missing fields')

def _integer(n: Any, low: int, high: int) -> None:
    if type(n) is not int or not low <= n <= high: fail('invalid integer')

def _text(s: Any, maximum: int, minimum: int = 0, note: bool = False) -> None:
    if type(s) is not str or not minimum <= len(s) <= maximum: fail('invalid text length')
    if minimum and not s.strip(): fail('empty required text')
    if any(ord(c) < 32 and not (note and c in '\n\t') for c in s): fail('control character')

def _uuid(s: Any) -> None:
    if not isinstance(s, str): fail('invalid id')
    try:
        if str(uuid.UUID(s)) != s: fail('id is not canonical')
    except (ValueError, AttributeError): fail('invalid id')

def _timestamps(row: dict) -> None:
    _integer(row['createdAt'],0,4102444800000); _integer(row['updatedAt'],0,4102444800000)
    if row['updatedAt'] < row['createdAt']: fail('timestamp order')

def validate(value: Any) -> dict:
    _keys(value, {'format','schemaVersion','exportedAt','children','subjects','exams','scores'})
    if value['format'] != 'scoreledger-backup' or type(value['schemaVersion']) is not int or value['schemaVersion'] != 1:
        fail('unsupported backup format/version')
    stamp = value['exportedAt']
    if type(stamp) is not str or not re.fullmatch(r'\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d{1,3})?Z',stamp): fail('invalid exportedAt')
    try: datetime.fromisoformat(stamp.replace('Z','+00:00'))
    except ValueError: fail('invalid exportedAt')
    for name,maximum in [('children',20),('subjects',100),('exams',10000),('scores',100000)]:
        if type(value[name]) is not list or len(value[name]) > maximum: fail('collection exceeds limit')
    children: set[str] = set(); subjects: set[str] = set(); exams: set[str] = set()
    for r in value['children']:
        _keys(r,{'id','nickname','gradeLabel','archived','createdAt','updatedAt'})
        _uuid(r['id']); _text(r['nickname'],24,1); _text(r['gradeLabel'],30); _timestamps(r)
        if type(r['archived']) is not bool: fail('invalid archived')
        if r['id'] in children: fail('duplicate child id')
        children.add(r['id'])
    for r in value['subjects']:
        _keys(r,{'id','name','defaultMaxMinor','sortOrder','archived','createdAt','updatedAt'})
        _uuid(r['id']); _text(r['name'],30,1); _integer(r['defaultMaxMinor'],1,1000000)
        _integer(r['sortOrder'],0,10000); _timestamps(r)
        if type(r['archived']) is not bool: fail('invalid archived')
        if r['id'] in subjects: fail('duplicate subject id')
        subjects.add(r['id'])
    for r in value['exams']:
        _keys(r,{'id','childId','title','examDate','kind','term','gradeSnapshot','note','createdAt','updatedAt'})
        _uuid(r['id']); _uuid(r['childId']); _text(r['title'],60,1); _text(r['term'],40)
        _text(r['gradeSnapshot'],30); _text(r['note'],500,note=True); _timestamps(r)
        if r['childId'] not in children: fail('orphan exam')
        if r['kind'] not in ('UNIT','MONTHLY','MIDTERM','FINAL','OTHER'): fail('unknown exam kind')
        if type(r['examDate']) is not str or not re.fullmatch(r'\d{4}-\d{2}-\d{2}',r['examDate']): fail('invalid exam date')
        try: date.fromisoformat(r['examDate'])
        except ValueError: fail('invalid exam date')
        if r['id'] in exams: fail('duplicate exam id')
        exams.add(r['id'])
    counts = dict.fromkeys(exams,0); seen: set[tuple[str,str]] = set()
    for r in value['scores']:
        _keys(r,{'examId','subjectId','subjectNameSnapshot','status','scoreMinor','maxMinor'})
        _uuid(r['examId']);_uuid(r['subjectId']);_text(r['subjectNameSnapshot'],30,1)
        if r['examId'] not in exams or r['subjectId'] not in subjects: fail('orphan score')
        pair = (r['examId'],r['subjectId'])
        if pair in seen: fail('duplicate score')
        seen.add(pair); counts[r['examId']] += 1
        _integer(r['maxMinor'],1,1000000)
        if r['status'] == 'SCORED': _integer(r['scoreMinor'],0,r['maxMinor'])
        elif r['status'] in ('ABSENT','MISSING'):
            if r['scoreMinor'] is not None: fail('missing is not zero')
        else: fail('unknown score status')
    if any(n < 1 or n > 30 for n in counts.values()): fail('invalid per-exam score count')
    return value

def loads(raw: bytes) -> dict:
    if not isinstance(raw,bytes) or len(raw) > MAX_BYTES: fail('backup too large or wrong input type')
    try:
        text=raw.decode('utf-8-sig')
        value=json.loads(text,object_pairs_hook=_pairs,parse_constant=lambda _: fail('non-finite number'))
        return validate(value)
    except (UnicodeDecodeError,json.JSONDecodeError,RecursionError) as exc:
        raise BackupError('invalid JSON/encoding/depth') from exc

if __name__ == '__main__':
    import argparse
    from pathlib import Path
    p=argparse.ArgumentParser();p.add_argument('backup');a=p.parse_args()
    with Path(a.backup).open('rb') as f: raw=f.read(MAX_BYTES+1)
    v=loads(raw)
    print(json.dumps({key:len(v[key]) for key in ('children','subjects','exams','scores')}))
