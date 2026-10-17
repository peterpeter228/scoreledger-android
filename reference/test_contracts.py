import unittest, json, copy, uuid
from pathlib import Path
from backup_validator import loads, validate, BackupError, MAX_BYTES

ROOT=Path(__file__).resolve().parents[1]
class BackupTests(unittest.TestCase):
    def setUp(self): self.sample=json.loads((ROOT/'fixtures/backup-valid.json').read_text())
    def bad(self, change):
        x=copy.deepcopy(self.sample);change(x)
        with self.assertRaises(BackupError): validate(x)
    def test_unpaired_surrogates(self):
        self.bad(lambda x:x['children'][0].update(nickname='\ud800'))
        self.bad(lambda x:x['exams'][0].update(note='\udc00'))
    def test_unicode_blank(self):
        self.bad(lambda x:x['children'][0].update(nickname='\u0085\u00a0'))
    def test_negative_zero_rejected(self):
        raw=json.dumps(self.sample).replace('"scoreMinor": 8000','"scoreMinor": -0',1).encode()
        with self.assertRaises(BackupError): loads(raw)
    def test_cap_and_future_preserved(self):
        self.assertEqual(MAX_BYTES,100663296)
        self.sample['exams'][0]['examDate']='2099-12-31'
        self.sample['children'][0]['nickname']='  保留空格  '
        self.assertEqual(loads(json.dumps(self.sample).encode()),self.sample)
    def test_valid(self): self.assertEqual(len(validate(self.sample)['exams']),3)
    def test_roundtrip(self): self.assertEqual(loads(json.dumps(self.sample,ensure_ascii=False).encode()),self.sample)
    def test_empty(self): self.assertEqual(loads((ROOT/'fixtures/backup-empty.json').read_bytes())['exams'],[])
    def test_unknown_version(self): self.bad(lambda x:x.update(schemaVersion=2))
    def test_boolean_version(self): self.bad(lambda x:x.update(schemaVersion=True))
    def test_wrong_format(self): self.bad(lambda x:x.update(format='other'))
    def test_entitlement_injection(self): self.bad(lambda x:x.update(isPro=True))
    def test_missing_field(self): self.bad(lambda x:x.pop('children'))
    def test_duplicate_child(self): self.bad(lambda x:x['children'].append(copy.deepcopy(x['children'][0])))
    def test_duplicate_subject(self): self.bad(lambda x:x['subjects'].append(copy.deepcopy(x['subjects'][0])))
    def test_duplicate_exam(self): self.bad(lambda x:x['exams'].append(copy.deepcopy(x['exams'][0])))
    def test_duplicate_score(self): self.bad(lambda x:x['scores'].append(copy.deepcopy(x['scores'][0])))
    def test_orphan_exam(self): self.bad(lambda x:x['exams'][0].update(childId=str(uuid.uuid4())))
    def test_orphan_score_subject(self): self.bad(lambda x:x['scores'][0].update(subjectId=str(uuid.uuid4())))
    def test_orphan_score_exam(self): self.bad(lambda x:x['scores'][0].update(examId=str(uuid.uuid4())))
    def test_absent_zero(self): self.bad(lambda x:x['scores'][0].update(status='ABSENT',scoreMinor=0))
    def test_missing_zero(self): self.bad(lambda x:x['scores'][0].update(status='MISSING',scoreMinor=0))
    def test_scored_null(self): self.bad(lambda x:x['scores'][0].update(scoreMinor=None))
    def test_zero_valid(self):
        self.sample['scores'][0]['scoreMinor']=0;validate(self.sample)
    def test_zero_max(self): self.bad(lambda x:x['scores'][0].update(maxMinor=0))
    def test_over_max(self): self.bad(lambda x:x['scores'][0].update(scoreMinor=10001))
    def test_negative(self): self.bad(lambda x:x['scores'][0].update(scoreMinor=-1))
    def test_float_integer(self): self.bad(lambda x:x['scores'][0].update(scoreMinor=8000.0))
    def test_boolean_integer(self): self.bad(lambda x:x['scores'][0].update(scoreMinor=True))
    def test_unknown_status(self): self.bad(lambda x:x['scores'][0].update(status='GREAT'))
    def test_bad_uuid(self): self.bad(lambda x:x['children'][0].update(id='../file'))
    def test_no_exam_rows(self): self.bad(lambda x:x.update(scores=x['scores'][3:]))
    def test_bad_leap_day(self): self.bad(lambda x:x['exams'][0].update(examDate='2025-02-29'))
    def test_bad_stamp(self): self.bad(lambda x:x.update(exportedAt='yesterday'))
    def test_timestamp_order(self): self.bad(lambda x:x['children'][0].update(updatedAt=1))
    def test_name_limit(self): self.bad(lambda x:x['children'][0].update(nickname='字'*25))
    def test_blank_name(self): self.bad(lambda x:x['children'][0].update(nickname='   '))
    def test_control_character(self): self.bad(lambda x:x['children'][0].update(nickname='a\nb'))
    def test_oversized_collection(self): self.bad(lambda x:x.update(children=x['children']*21))
    def test_duplicate_json_key(self):
        with self.assertRaises(BackupError): loads(b'{"schemaVersion":1,"schemaVersion":1}')
    def test_nan(self):
        with self.assertRaises(BackupError): loads(b'{"x":NaN}')
    def test_bad_encoding(self):
        with self.assertRaises(BackupError): loads(b'\xff')
    def test_truncated(self):
        with self.assertRaises(BackupError): loads(b'{')
    def test_oversized_file(self):
        with self.assertRaises(BackupError): loads(b' '*(MAX_BYTES+1))
    def test_bom_accepted(self): self.assertEqual(loads(b'\xef\xbb\xbf'+json.dumps(self.sample).encode()),self.sample)
    def test_sql_name_is_data(self):
        self.sample['children'][0]['nickname']="';DROP TABLE exams;--";validate(self.sample)
    def test_repeat_restore_model_idempotent(self):
        a=loads(json.dumps(self.sample).encode());b=loads(json.dumps(a).encode());self.assertEqual(a,b)

if __name__=='__main__': unittest.main()
