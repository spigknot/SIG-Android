"""Offline label-based position decoder for reference q8_0 tiled HVX.

Not an arithmetic qf16/qf32 emulator, and not proof of cohort source identity.
No fitting, matching or permutation learned from payload or actfloat.
Qualcomm v81 instruction *Behavior* pseudocode governs label movement.
"""
import hashlib
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parent
SOURCE_DIR = Path('D:/Projetos/SIG/app/src/main/cpp/llama/ggml/src/ggml-hexagon/htp')
KERNEL_HASH = '0df0307926357489e8fab6d963e921db73c83395c6aac52181a1a4efea5ae7f5'
SOURCE_HASHES = {
    'hvx-mm-kernels-tiled.h': KERNEL_HASH,
    'hvx-base.h': '1036c5ebcad4cd1a437f0706ddf9c167e57be2272e375806be4e47e24d8e7503',
    'hvx-repl.h': '8b9fc852ad3a324d55cc060b6bb7bc6206c7350651741ca69f57fa20b0c2e759',
    'hvx-reduce.h': '90abf4703709475bc4e1b03b46ac6b1c2159feabc529a88f04ceed7c42086330',
    'htp-ops.h': 'd61e60b0e3f3ea7ab7eca667ef4f5423615a82f93460add73f3af19ccb0951c4',
}

def load_source_masks(source_dir=SOURCE_DIR):
    source_bytes = {name:(Path(source_dir)/name).read_bytes() for name in SOURCE_HASHES}
    for name,expected in SOURCE_HASHES.items():
        if hashlib.sha256(source_bytes[name]).hexdigest() != expected:
            raise ValueError('Reference source changed: '+name+'; re-audit required (not a cohort pin)')
    sources = [source_bytes['hvx-mm-kernels-tiled.h'].decode('utf-8'),
               source_bytes['hvx-repl.h'].decode('utf-8')]
    symbols = ['quantize_block_f32_q8_0_tiled', 'hvx_vec_repl_f16']
    result = {}
    for text, symbol, key in zip(sources, symbols, ['activation_repl', 'scale_repl']):
        body = text.split(symbol + '(', 1)[1]
        match = re.search(r'repl\[128\]\s*=\s*\{(.*?)\};', body, re.S)
        if match is None:
            raise ValueError('Missing explicit 128-byte mask for ' + symbol)
        values = [int(x, 16) for x in re.findall(r'0x[0-9a-fA-F]+', match.group(1))]
        if len(values) != 128:
            raise ValueError('Invalid mask length')
        result[key] = values
    return result

def vdelta_labels(vector, controls):
    """v81 vdelta Behavior: descending 64,32,16,8,4,2,1 stages.

    Each stage reads the whole previous stage (not in-place); control[k]
    is consulted anew at destination k at every stage, not a single XOR.
    """
    if len(vector) != 128 or len(controls) != 128:
        raise ValueError('HVX width must be 128 bytes')
    current = list(vector)
    for offset in (64, 32, 16, 8, 4, 2, 1):
        current = [current[k ^ offset] if controls[k] & offset else current[k]
                   for k in range(128)]
    return current


def qf32_pair_to_hf_labels(hi, lo):
    # Vd.hf=Vuu.qf32: output[2*i]=lo[i], output[2*i+1]=hi[i].
    if len(hi) != 32 or len(lo) != 32:
        raise ValueError('Expected two 32-element qf32 label vectors')
    return [x for pair in zip(lo, hi) for x in pair]


def deal_hf_labels(vector):
    # Vd.h=vdeal(Vu.h): even halfwords to lower half, odds to upper.
    if len(vector) != 64:
        raise ValueError('Expected 64 halfword labels')
    return list(vector[::2]) + list(vector[1::2])


def hf_to_qf32_pair_labels(vector):
    # Q6_Wqf32_vmpy_VhfVhf: lower=even halfwords, upper=odd halfwords.
    if len(vector) != 64:
        raise ValueError('Expected 64 halfword labels')
    return list(vector[1::2]), list(vector[::2])  # return hi,lo


def vround_i16_labels(hi, lo):
    # v81 Behavior table (not its contradictory introductory prose):
    # Vd.uw[i].h[0]=sat_16((Vv.w[i]+0x8000)>>16), h[1] uses Vu.
    return qf32_pair_to_hf_labels(hi, lo)


def vpack_i8_labels(hi, lo):
    # Vd.b[i]=sat_8(Vv.h[i]); Vd.b[i+64]=sat_8(Vu.h[i]).
    if len(hi) != 64 or len(lo) != 64:
        raise ValueError('Expected two 64-halfword label vectors')
    return list(lo) + list(hi)


def vror_labels(vector, rt):
    if len(vector) != 128:
        raise ValueError('Expected 128 byte labels')
    return [vector[(k + rt) & 127] for k in range(128)]


def simulate_positions(source_dir=SOURCE_DIR):
    """Propagate labels 0..127 through each lane-changing operator.

    Lane-local arithmetic is deliberately NOT evaluated; qf formats are not
    treated as IEEE FP16. A label denotes input ancestry only, not any bits.
    """
    from collections import Counter
    masks = load_source_masks(source_dir)
    trace = {'source_identity': 'LOCAL_REFERENCE_NOT_PROVEN_R50_COHORT',
             'arithmetic_replay': False}
    for group in (0, 1):
        name = 'vx01' if group == 0 else 'vx23'
        lo = list(range(group*64, group*64+32))
        hi = list(range(group*64+32, group*64+64))
        before_deal = qf32_pair_to_hf_labels(hi, lo)
        trace[name+'_hf_before_deal'] = before_deal
        hf = deal_hf_labels(before_deal)
        trace[name+'_hf'] = hf
        wide_hi, wide_lo = hf_to_qf32_pair_labels(hf)
        trace[name+'_i16_lo_qf32'] = wide_lo
        trace[name+'_i16_hi_qf32'] = wide_hi
        trace[name+'_i16'] = vround_i16_labels(wide_hi, wide_lo)
    packed = vpack_i8_labels(trace['vx23_i16'], trace['vx01_i16'])
    trace['vx_i8'] = packed
    q_positions = []
    for b in range(4):
        act = vror_labels(packed, b*32)
        for r in range(8):
            q_positions.extend(vdelta_labels(vror_labels(act, 4*r),
                                            masks['activation_repl']))
    trace['q_positions'] = q_positions
    trace['scale_byte_positions'] = vdelta_labels(list(range(128)), masks['scale_repl'])
    trace['masks'] = masks
    formula = [32*b+4*r+j%4 for b in range(4) for r in range(8) for j in range(128)]
    if q_positions != formula or Counter(q_positions) != Counter({i:32 for i in range(128)}):
        raise ValueError('Symbolic labels disagree with claimed closed form')
    if trace['scale_byte_positions'] != [j%2 for j in range(128)]:
        raise ValueError('Scale mask does not replicate first halfword')
    return trace


def decode_payload(payload, strict=True, source_dir=SOURCE_DIR):
    """Decode exactly once by the fixed symbolic mapping, then check replicas.

    First observed byte is canonical; no majority voting, sorting, fitting,
    permutation search, actfloat input or payload-derived mapping is used.
    """
    import struct
    if not payload or len(payload) % 4608:
        raise ValueError('Payload must contain complete 4608-byte blocks')
    trace = simulate_positions(source_dir)
    report = {'payload_size':len(payload), 'payload_sha256':hashlib.sha256(payload).hexdigest(),
              'blocks':len(payload)//4608, 'logical_values':len(payload)//4608*128,
              'q_replica_mismatches':0, 'q_replica_comparisons':0,
              'scale_replica_mismatches':0, 'scale_replica_comparisons':0,
              'q_observations':0, 'scale_halfword_observations':0,
              'mismatch_examples':[], 'per_block':[],
              'source_identity':'LOCAL_REFERENCE_NOT_PROVEN_R50_COHORT',
              'arithmetic_replay':False, 'mapping_derived_from_payload':False}
    q_out = bytearray()
    scale_out = bytearray()
    dequantized = []
    for k in range(report['blocks']):
        q_values = [None]*128
        q_offsets = [None]*128
        q_counts = [0]*128
        sub_distinct = []
        q_bad = scale_bad = 0
        scale = None
        scale_offset = None
        for b in range(4):
            labels = set()
            for r in range(8):
                for lane in range(128):
                    label = trace['q_positions'][b*1024+r*128+lane]
                    off = k*4608+b*1152+r*128+lane
                    value = payload[off]
                    labels.add(label)
                    q_counts[label] += 1
                    report['q_observations'] += 1
                    if q_values[label] is None:
                        q_values[label], q_offsets[label] = value, off
                    else:
                        report['q_replica_comparisons'] += 1
                        if value != q_values[label]:
                            q_bad += 1
                            if len(report['mismatch_examples']) < 20:
                                report['mismatch_examples'].append({'kind':'q','block':k,
                                    'input_index':128*k+label,'offset':off,'first_offset':q_offsets[label],
                                    'observed_u8':value,'first_u8':q_values[label]})
            sub_distinct.append(sorted(labels))
            for h in range(64):
                off = k*4608+b*1152+1024+2*h
                value = payload[off:off+2]
                report['scale_halfword_observations'] += 1
                if scale is None:
                    scale, scale_offset = value, off
                else:
                    report['scale_replica_comparisons'] += 1
                    if value != scale:
                        scale_bad += 1
                        if len(report['mismatch_examples']) < 20:
                            report['mismatch_examples'].append({'kind':'scale','block':k,
                                'offset':off,'first_offset':scale_offset,
                                'observed_hex':value.hex(),'first_hex':scale.hex()})
        if q_counts != [32]*128 or any(v is None for v in q_values):
            raise ValueError('Wrong symbolic coverage')
        scale_bits = struct.unpack('<H',scale)[0]
        scale_fp16 = struct.unpack('<e',scale)[0]
        report['q_replica_mismatches'] += q_bad
        report['scale_replica_mismatches'] += scale_bad
        report['per_block'].append({'block':k,'q_replica_mismatches':q_bad,
            'scale_replica_mismatches':scale_bad,'scale_bits_u16':scale_bits,
            'scale_fp16':scale_fp16,'replicas_per_input_min':min(q_counts),
            'replicas_per_input_max':max(q_counts),'input_indices_per_subtile':sub_distinct})
        q_out.extend(q_values)
        scale_out.extend(scale)
        dequantized.extend((v if v < 128 else v-256)*scale_fp16 for v in q_values)
    report['all_replicas_consistent'] = report['q_replica_mismatches'] == 0 and report['scale_replica_mismatches'] == 0
    if strict and not report['all_replicas_consistent']:
        raise ValueError('Positional replicas inconsistent: ' + str(report['mismatch_examples']))
    return {'q_bytes':bytes(q_out),'scale_fp16_le':bytes(scale_out),
            'dequantized':dequantized,'report':report,'trace':trace}


def _write_artifacts(output_dir, result):
    import csv
    import json
    import struct
    output_dir = Path(output_dir).resolve()
    if not output_dir.is_relative_to(ROOT):
        raise ValueError('All outputs must be inside this position-child scratch directory')
    output_dir.mkdir(parents=True, exist_ok=True)
    trace = result['trace']
    (output_dir/'label_trace.json').write_text(json.dumps(trace,indent=2),encoding='utf-8')
    (output_dir/'validation_report.json').write_text(json.dumps(result['report'],indent=2),encoding='utf-8')
    with (output_dir/'block_byte_mapping.csv').open('w',newline='',encoding='utf-8') as stream:
        writer = csv.writer(stream)
        writer.writerow(['block_relative_offset','field','subtile','vector','lane_byte','input_index_within_block','scale_byte'])
        for b in range(4):
            for r in range(8):
                for j in range(128):
                    writer.writerow([1152*b+128*r+j,'q_i8',b,r,j,
                                     trace['q_positions'][1024*b+128*r+j],''])
            for j in range(128):
                writer.writerow([1152*b+1024+j,'scale_fp16_le',b,8,j,'',trace['scale_byte_positions'][j]])
    if result['report']['all_replicas_consistent']:
        (output_dir/'decoded_q_i8.bin').write_bytes(result['q_bytes'])
        (output_dir/'decoded_scales_fp16_le.bin').write_bytes(result['scale_fp16_le'])
        (output_dir/'dequantized_q_times_d_f32_le.bin').write_bytes(struct.pack('<'+str(len(result['dequantized']))+'f',*result['dequantized']))


def main():
    import argparse
    import json
    import sys
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--payload',type=Path,default=Path('D:/Projetos/SIG/docs/npu-hymt2/smart-rodada50/act_payload.bin'))
    parser.add_argument('--twin',type=Path,default=Path('D:/Projetos/SIG/docs/npu-hymt2/smart-rodada50/act_payload_2.bin'))
    parser.add_argument('--out',type=Path,default=ROOT/'verified')
    args = parser.parse_args()
    try:
        payload_bytes = args.payload.read_bytes()
        result = decode_payload(payload_bytes,strict=True)
        twin_bytes = args.twin.read_bytes()
        twin = decode_payload(twin_bytes,strict=True)
    except (ValueError,OSError) as exc:
        print('POSITION_FAIL:',str(exc),file=sys.stderr)
        return 1
    if (payload_bytes != twin_bytes or result['q_bytes'] != twin['q_bytes'] or
            result['scale_fp16_le'] != twin['scale_fp16_le']):
        print('POSITION_FAIL: TWIN_MISMATCH; no decoded payload exported',file=sys.stderr)
        return 1
    result['report']['twin'] = {'path':str(args.twin.resolve()),
        'sha256':twin['report']['payload_sha256'],'payload_equal':args.payload.read_bytes()==twin_bytes,
        'decoded_q_equal':result['q_bytes']==twin['q_bytes'],
        'decoded_scales_equal':result['scale_fp16_le']==twin['scale_fp16_le'],
        'all_replicas_consistent':twin['report']['all_replicas_consistent'],
        'q_replica_mismatches':twin['report']['q_replica_mismatches'],
        'scale_replica_mismatches':twin['report']['scale_replica_mismatches']}
    _write_artifacts(args.out,result)
    summary = {key:val for key,val in result['report'].items() if key != 'per_block'}
    print(json.dumps(summary,indent=2))
    print('ARTIFACTS',str(args.out.resolve()))
    return 0 if result['report']['all_replicas_consistent'] and twin['report']['all_replicas_consistent'] else 1

if __name__ == '__main__':
    raise SystemExit(main())
