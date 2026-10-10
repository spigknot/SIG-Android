import importlib.util
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('position_decoder', ROOT / 'position_decoder.py')
if spec is not None and spec.loader is not None and (ROOT / 'position_decoder.py').exists():
    decoder = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(decoder)
else:
    decoder = None

class PositionTests(unittest.TestCase):
    def test_vdelta_source_mask_replicates_first_four_bytes(self):
        self.assertIsNotNone(decoder, 'positional simulator is not implemented')
        masks = decoder.load_source_masks()
        actual = decoder.vdelta_labels(list(range(128)), masks['activation_repl'])
        self.assertEqual(actual, [i % 4 for i in range(128)])
        self.assertEqual(decoder.vdelta_labels(list(range(128)), masks['scale_repl']),
                         [i % 2 for i in range(128)])

    def test_pre_tile_pipeline_is_identity_not_assumed(self):
        self.assertTrue(hasattr(decoder, 'simulate_positions'), 'label pipeline missing')
        trace = decoder.simulate_positions()
        self.assertEqual(trace['vx01_hf_before_deal'], [v for i in range(32) for v in (i, 32+i)])
        self.assertEqual(trace['vx01_hf'], list(range(64)))
        self.assertEqual(trace['vx01_i16_lo_qf32'], list(range(0,64,2)))
        self.assertEqual(trace['vx01_i16_hi_qf32'], list(range(1,64,2)))
        self.assertEqual(trace['vx01_i16'], list(range(64)))
        self.assertEqual(trace['vx_i8'], list(range(128)))

    def test_every_physical_q_byte_has_a_label_32_replicas(self):
        self.assertTrue(hasattr(decoder, 'simulate_positions'), 'label pipeline missing')
        from collections import Counter
        trace = decoder.simulate_positions()
        mapping = trace['q_positions']
        self.assertEqual(len(mapping), 4096)
        self.assertEqual(Counter(mapping), Counter({i:32 for i in range(128)}))
        for s in range(4):
            per_sub = mapping[s*1024:(s+1)*1024]
            self.assertEqual(Counter(per_sub), Counter({i:32 for i in range(s*32,(s+1)*32)}))
            for r in range(8):
                for lane in range(128):
                    self.assertEqual(per_sub[r*128+lane], 32*s+4*r+lane%4)
        self.assertEqual(trace['scale_byte_positions'], [i%2 for i in range(128)])

    def test_round_and_pack_operand_order_is_explicit(self):
        self.assertTrue(hasattr(decoder, 'vround_i16_labels'), 'round label operator missing')
        lo = list(range(32))
        hi = list(range(100,132))
        self.assertEqual(decoder.vround_i16_labels(hi,lo), [v for i in range(32) for v in (i,100+i)])
        self.assertEqual(decoder.vpack_i8_labels(list(range(64,128)),list(range(64))), list(range(128)))

    def test_synthetic_decode_and_reject_inconsistency(self):
        self.assertTrue(hasattr(decoder, 'decode_payload'), 'payload decoder missing')
        # Fixtures assembled from the closed-form mapping, NOT the simulator.
        values = bytes((73*i+5)%256 for i in range(128))
        payload = bytearray()
        for s in range(4):
            for r in range(8):
                payload.extend(bytes(values[32*s+4*r+l%4] for l in range(128)))
            payload.extend(b'\x00\x3c'*64)
        result = decoder.decode_payload(bytes(payload))
        self.assertEqual(result['q_bytes'],values)
        self.assertEqual(result['report']['q_replica_mismatches'],0)
        self.assertEqual(result['report']['scale_replica_mismatches'],0)
        for off in (4,1024,1152+1024):
            bad = payload.copy()
            bad[off] ^= 1
            with self.assertRaises(ValueError):
                decoder.decode_payload(bytes(bad))
        with self.assertRaises(ValueError):
            decoder.decode_payload(bytes(payload[:-1]))

    def test_cli_different_consistent_twin_fails_before_export(self):
        import subprocess
        import sys
        import uuid
        base = ROOT / 'cli-tdd-fixtures' / uuid.uuid4().hex
        base.mkdir(parents=True)
        payload = bytearray()
        for s in range(4):
            for r in range(8):
                payload.extend(bytes(32*s+4*r+l%4 for l in range(128)))
            payload.extend(b'\x00\x3c'*64)
        twin = payload.copy()
        for lane in range(0,128,4):
            twin[lane] = 120
        (base/'payload.bin').write_bytes(payload)
        (base/'twin.bin').write_bytes(twin)
        out = base/'output'
        result = subprocess.run([sys.executable,str(ROOT/'position_decoder.py'),
            '--payload',str(base/'payload.bin'),'--twin',str(base/'twin.bin'),
            '--out',str(out)],capture_output=True,text=True)
        self.assertNotEqual(result.returncode,0,'different valid twin was accepted')
        self.assertFalse((out/'decoded_q_i8.bin').exists(),'payload published before twin gate')

if __name__ == '__main__':
    unittest.main()
