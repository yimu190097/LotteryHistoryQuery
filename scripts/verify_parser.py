#!/usr/bin/env python3
"""
校验元数据解析器：按各阶段政策，每个彩种取10期官方数据，对比解析结果。
复制 LotteryXlsParser.kt 的解析逻辑，逐字段输出差异。
"""
import sys, re, os
from collections import OrderedDict

# ============== 彩种配置（与 LotteryType.kt 完全一致）==============
LOTTERY_CONFIGS = {
    "ssq": {
        "name": "双色球", "primaryCount": 6, "secondaryCount": 1,
        "ruleVersions": [
            {"key": "ssq_20260201", "date": "2026-02-01", "label": "2026新规·含福运奖", "realTiers": 7, "prizePairs": 7, "extraFields": 8},
            {"key": "ssq_20030216", "date": "2003-02-16", "label": "2003-2026·经典6级", "realTiers": 6, "prizePairs": 6, "extraFields": 8},
        ]
    },
    "dlt": {
        "name": "大乐透", "primaryCount": 5, "secondaryCount": 2,
        "ruleVersions": [
            {"key": "dlt_20260131", "date": "2026-01-31", "label": "2026新规·7级", "realTiers": 7, "prizePairs": 7, "extraFields": 2, "appendPairs": 7, "appendRatio": 0.8},
            {"key": "dlt_20190218", "date": "2019-02-18", "label": "2019-2026·9级", "realTiers": 9, "prizePairs": 9, "extraFields": 2, "appendPairs": 7, "appendRatio": 0.8},
            {"key": "dlt_20140505", "date": "2014-05-05", "label": "2014-2019·6级", "realTiers": 6, "prizePairs": 6, "extraFields": 2, "appendPairs": 6, "appendRatio": 0.6},
            {"key": "dlt_20091017", "date": "2009-10-17", "label": "2009-2014·8级", "realTiers": 8, "prizePairs": 8, "extraFields": 2, "appendPairs": 7, "appendRatio": 0.6},
            {"key": "dlt_20070528", "date": "2007-05-28", "label": "2007-2009·上市首版", "realTiers": 8, "prizePairs": 8, "extraFields": 2, "appendPairs": 7, "appendRatio": 0.6},
        ]
    },
    "3d": {
        "name": "福彩3D", "primaryCount": 3, "secondaryCount": 0,
        "ruleVersions": [
            {"key": "3d_20041018", "date": "2004-10-18", "label": "2004至今·三档", "realTiers": 3, "prizePairs": 3, "extraFields": 6},
        ]
    },
    "7lc": {
        "name": "七乐彩", "primaryCount": 7, "secondaryCount": 1,
        "ruleVersions": [
            {"key": "7lc_2000", "date": "2000-01-01", "label": "2000至今·7级", "realTiers": 7, "prizePairs": 7, "extraFields": 2},
        ]
    },
    "p3": {
        "name": "排列三", "primaryCount": 3, "secondaryCount": 0,
        "ruleVersions": [
            {"key": "p3_20041218", "date": "2004-12-18", "label": "2004至今·三档", "realTiers": 3, "prizePairs": 3, "extraFields": 1},
        ]
    },
    "p5": {
        "name": "排列五", "primaryCount": 5, "secondaryCount": 0,
        "ruleVersions": [
            {"key": "p5_20041218", "date": "2004-12-18", "label": "2004至今·10万", "realTiers": 1, "prizePairs": 1, "extraFields": 1},
        ]
    },
    "7xc": {
        "name": "七星彩", "primaryCount": 6, "secondaryCount": 1,
        "ruleVersions": [
            {"key": "qxc_20201011", "date": "2020-10-11", "label": "2020后·固定奖升级", "realTiers": 6, "prizePairs": 6, "extraFields": 2},
            {"key": "qxc_20040518", "date": "2004-05-18", "label": "2004-2020·经典6级", "realTiers": 6, "prizePairs": 6, "extraFields": 2},
        ]
    },
    "kl8": {
        "name": "快乐8", "primaryCount": 20, "secondaryCount": 0,
        "ruleVersions": [
            {"key": "kl8_20201028", "date": "2020-10-28", "label": "2020至今·选十", "realTiers": 7, "prizePairs": 7, "extraFields": 2},
        ]
    },
}

DATA_FILES = {
    "ssq": "/tmp/lottery_data/ssq.txt",
    "dlt": "/tmp/lottery_data/dlt.txt",
    "3d": "/tmp/lottery_data/3d.txt",
    "7lc": "/tmp/lottery_data/7lc.txt",
    "p3": "/tmp/lottery_data/p3.txt",
    "p5": "/tmp/lottery_data/p5.txt",
    "7xc": "/tmp/lottery_data/7xc.txt",
    "kl8": "/tmp/lottery_data/kl8.txt",
}

def parse_number_safe(raw):
    """复制 LotteryXlsParser.parseNumberSafe 逻辑"""
    t = raw.strip()
    if not t or t == '-':
        return None
    if ',' not in t and '.' not in t:
        return int(t) if t.lstrip('-').isdigit() else None
    if ',' not in t:
        return int(t.split('.')[0]) if t.split('.')[0].lstrip('-').isdigit() else None
    # 美式千分位
    if re.match(r'^[+-]?\d{1,3}(?:,\d{3})*(?:\.\d+)?$', t):
        cleaned = t.replace(',', '')
        return int(cleaned.split('.')[0])
    # 欧式
    if re.match(r'^[+-]?\d{1,3}(?:\.\d{3})*(?:,\d+)?$', t):
        cleaned = t.replace('.', '')
        return int(cleaned.split(',')[0])
    return None

def normalize_date(raw):
    if re.match(r'^\d{4}-\d{2}-\d{2}$', raw):
        return raw
    if re.match(r'^\d{4}/\d{1,2}/\d{1,2}$', raw):
        p = raw.split('/')
        return f"{p[0]}-{p[1].zfill(2)}-{p[2].zfill(2)}"
    if re.match(r'^\d{4}\.\d{1,2}\.\d{1,2}$', raw):
        p = raw.split('.')
        return f"{p[0]}-{p[1].zfill(2)}-{p[2].zfill(2)}"
    return None

def parse_dlt(parts, rv):
    """DLT 专属解析（复制 LotteryXlsParser.kt 的字段布局）"""
    # 前缀 0-10: issue(0) + date(1) + 5前区(2-6) + 2后区(7-8) + sales(9) + jackpot(10)
    # 基本投注主体 11-24: 7对 = 字段12-25 (1-based)
    # 基本投注尾部扩展 25-28: 2对 = 字段26-29
    # 追加投注 1-4等 29-36: 4对 = 字段30-37
    # 追加投注5级count 37: 字段38
    base_first7 = []
    for i in range(7):
        c = parse_number_safe(parts[11 + i*2]) if len(parts) > 11 + i*2 else None
        a = parse_number_safe(parts[12 + i*2]) if len(parts) > 12 + i*2 else None
        base_first7.append((c, a))
    
    base_tail2 = []
    for i in range(2):
        c = parse_number_safe(parts[25 + i*2]) if len(parts) > 25 + i*2 else None
        a = parse_number_safe(parts[26 + i*2]) if len(parts) > 26 + i*2 else None
        base_tail2.append((c, a))
    
    base_full = base_first7 + base_tail2
    all_tiers = base_full[:rv["realTiers"]]
    
    # 追加投注
    append_first4 = []
    for i in range(4):
        c = parse_number_safe(parts[29 + i*2]) if len(parts) > 29 + i*2 else None
        a = parse_number_safe(parts[30 + i*2]) if len(parts) > 30 + i*2 else None
        append_first4.append((c, a))
    
    ratio = rv.get("appendRatio", 0.8)
    is_pre2019 = rv["key"] in ["dlt_20070528", "dlt_20091017", "dlt_20140505"]
    append5_raw = parts[37] if len(parts) > 37 else None
    if is_pre2019:
        append5_count = 0  # 2009版 F38=60 尾标忽略
    else:
        append5_count = parse_number_safe(append5_raw) if append5_raw and append5_raw != '-' else 0
    
    base5_amt = base_first7[4][1] if base_first7[4][1] else 0
    base6_amt = base_first7[5][1] if base_first7[5][1] else 0
    base7_amt = base_first7[6][1] if base_first7[6][1] else 0
    
    append_tail = [
        (append5_count, int(base5_amt * ratio)),
        (0, int(base6_amt * ratio)),
        (0, int(base7_amt * ratio)),
    ]
    append_tiers = append_first4 + append_tail
    append_tiers = append_tiers[:rv["appendPairs"]]
    
    return all_tiers, append_tiers

def parse_generic(parts, rv, has_secondary):
    """通用解析（非DLT彩种）"""
    extra_start = 2 + rv["primaryCount"] + rv["secondaryCount"]
    prize_start = extra_start + rv["extraFields"]
    all_tiers = []
    for i in range(rv["prizePairs"]):
        ci = prize_start + i * 2
        ai = prize_start + i * 2 + 1
        if ci >= len(parts) or ai >= len(parts):
            all_tiers.append(None)
            continue
        c_raw = parts[ci] if ci < len(parts) else None
        a_raw = parts[ai] if ai < len(parts) else None
        if c_raw == '-' or a_raw == '-':
            all_tiers.append(None)
            continue
        c = parse_number_safe(c_raw) if c_raw else None
        a = parse_number_safe(a_raw) if a_raw else None
        all_tiers.append((c, a) if c is not None and a is not None else None)
    return all_tiers, []

def rules_for_date(rvs, date_str):
    for rv in rvs:
        if date_str >= rv["date"]:
            return rv
    return None

def parse_line(code, cfg, parts):
    """解析一行数据，返回 parsed dict"""
    issue = parts[0]
    date = normalize_date(parts[1])
    if not date:
        return None
    
    primary_count = cfg["primaryCount"]
    secondary_count = cfg["secondaryCount"]
    primary = [int(parts[2 + i]) for i in range(primary_count) if parts[2 + i].isdigit()]
    if len(primary) != primary_count:
        return None
    
    secondary = []
    if secondary_count > 0:
        sec_start = 2 + primary_count
        secondary = [int(parts[sec_start + i]) for i in range(secondary_count) if parts[sec_start + i].isdigit()]
    
    rv = rules_for_date(cfg["ruleVersions"], date)
    if rv is None:
        return None
    
    rv_with_counts = {**rv, "primaryCount": primary_count, "secondaryCount": secondary_count}
    
    if code == "dlt":
        all_tiers, append_tiers = parse_dlt(parts, rv_with_counts)
    else:
        all_tiers, append_tiers = parse_generic(parts, rv_with_counts, secondary_count > 0)
    
    # 提取销售额和奖池
    extra_start = 2 + primary_count + secondary_count
    ef = rv["extraFields"]
    sales = None
    jackpot = None
    if ef >= 2:
        sales = parse_number_safe(parts[extra_start + ef - 2]) if len(parts) > extra_start + ef - 2 else None
        jackpot = parse_number_safe(parts[extra_start + ef - 1]) if len(parts) > extra_start + ef - 1 else None
    elif ef == 1:
        sales = parse_number_safe(parts[extra_start]) if len(parts) > extra_start else None
    
    return {
        "issue": issue, "date": date, "primary": primary, "secondary": secondary,
        "allTiers": all_tiers, "appendTiers": append_tiers,
        "sales": sales, "jackpot": jackpot,
        "ruleVersion": rv["key"], "policyLabel": rv["label"],
        "rawParts": parts
    }

def select_period_draws(code, cfg, parsed_draws):
    """为每个政策阶段选10期"""
    period_draws = {}
    for rv in cfg["ruleVersions"]:
        matching = [d for d in parsed_draws if d["ruleVersion"] == rv["key"]]
        if len(matching) >= 10:
            # 取最新10期（按issue降序）
            matching.sort(key=lambda d: d["issue"], reverse=True)
            period_draws[rv["label"]] = matching[:10]
        elif len(matching) > 0:
            period_draws[rv["label"]] = matching
    return period_draws

def load_and_parse(code, cfg):
    filepath = DATA_FILES[code]
    if not os.path.exists(filepath):
        print(f"  ⚠ 数据文件不存在: {filepath}")
        return []
    
    with open(filepath, 'r') as f:
        content = f.read()
    
    # 跳过文件开头非数据行
    parsed = []
    for line in content.split('\n'):
        line = line.strip()
        if not line:
            continue
        parts = line.split()
        if len(parts) < 5:
            continue
        # 期号必须是数字
        if not re.match(r'^\d{5,}$', parts[0]):
            continue
        result = parse_line(code, cfg, parts)
        if result:
            parsed.append(result)
    
    print(f"  共解析 {len(parsed)} 行")
    return parsed

def compare_draw(code, cfg, draw):
    """对比单期解析结果与官方原始数据"""
    errors = []
    parts = draw["rawParts"]
    rv_key = draw["ruleVersion"]
    rv = rules_for_date(cfg["ruleVersions"], draw["date"])
    
    if rv is None:
        errors.append("无法定位规则版本")
        return errors
    
    # 对比基本投注奖级
    if code == "dlt":
        # DLT: 字段 12-25 (7对基本) + 26-29 (尾部扩展)
        base_first7_fields = []
        for i in range(7):
            c = parse_number_safe(parts[11 + i*2]) if len(parts) > 11 + i*2 else None
            a = parse_number_safe(parts[12 + i*2]) if len(parts) > 12 + i*2 else None
            base_first7_fields.append((c, a))
        base_tail2_fields = []
        for i in range(2):
            c = parse_number_safe(parts[25 + i*2]) if len(parts) > 25 + i*2 else None
            a = parse_number_safe(parts[26 + i*2]) if len(parts) > 26 + i*2 else None
            base_tail2_fields.append((c, a))
        base_full_fields = base_first7_fields + base_tail2_fields
        expected_base = base_full_fields[:rv["realTiers"]]
        
        for i in range(min(len(expected_base), len(draw["allTiers"]))):
            exp = expected_base[i]
            got = draw["allTiers"][i]
            if got is None and exp == (None, None):
                continue
            if got is None or exp[0] != got[0] or exp[1] != got[1]:
                errors.append(f"基本投注第{i+1}对: 官方={exp}, 解析={got}")
        
        # 对比追加投注
        append_pair_count = rv.get("appendPairs", 0)
        if append_pair_count > 0:
            append_first4_fields = []
            for i in range(4):
                c = parse_number_safe(parts[29 + i*2]) if len(parts) > 29 + i*2 else None
                a = parse_number_safe(parts[30 + i*2]) if len(parts) > 30 + i*2 else None
                append_first4_fields.append((c, a))
            
            ratio = rv.get("appendRatio", 0.8)
            is_pre2019 = rv["key"] in ["dlt_20070528", "dlt_20091017", "dlt_20140505"]
            append5_raw = parts[37] if len(parts) > 37 else None
            if is_pre2019:
                append5_count = 0
            else:
                append5_count = parse_number_safe(append5_raw) if append5_raw and append5_raw != '-' else 0
            
            base5_amt = base_first7_fields[4][1] if base_first7_fields[4][1] else 0
            base6_amt = base_first7_fields[5][1] if base_first7_fields[5][1] else 0
            base7_amt = base_first7_fields[6][1] if base_first7_fields[6][1] else 0
            
            expected_append = append_first4_fields + [
                (append5_count, int(base5_amt * ratio)),
                (0, int(base6_amt * ratio)),
                (0, int(base7_amt * ratio)),
            ]
            expected_append = expected_append[:append_pair_count]
            
            for i in range(min(len(expected_append), len(draw["appendTiers"]))):
                exp = expected_append[i]
                got = draw["appendTiers"][i]
                if got is None and exp == (None, None):
                    continue
                if got is None or exp[0] != got[0] or exp[1] != got[1]:
                    errors.append(f"追加投注第{i+1}对: 官方={exp}, 解析={got}")
    else:
        # 通用彩种
        extra_start = 2 + cfg["primaryCount"] + cfg["secondaryCount"]
        prize_start = extra_start + rv["extraFields"]
        for i in range(rv["prizePairs"]):
            ci = prize_start + i * 2
            ai = prize_start + i * 2 + 1
            if ci >= len(parts) or ai >= len(parts):
                continue
            c_raw = parts[ci] if ci < len(parts) else None
            a_raw = parts[ai] if ai < len(parts) else None
            exp_c = parse_number_safe(c_raw) if c_raw and c_raw != '-' else None
            exp_a = parse_number_safe(a_raw) if a_raw and a_raw != '-' else None
            exp = (exp_c, exp_a) if exp_c is not None or exp_a is not None else None
            
            got = draw["allTiers"][i] if i < len(draw["allTiers"]) else None
            if exp == (None, None) and (got is None or got == (None, None)):
                continue
            if got != exp:
                errors.append(f"奖级第{i+1}对: 官方={exp}, 解析={got}")
    
    return errors

def main():
    print("=" * 70)
    print("  元数据解析器校验：官方数据 vs 解析结果")
    print("=" * 70)
    
    total_errors = 0
    total_checked = 0
    
    for code, cfg in LOTTERY_CONFIGS.items():
        print(f"\n{'='*70}")
        print(f"  {cfg['name']} ({code})")
        print(f"{'='*70}")
        
        parsed = load_and_parse(code, cfg)
        if not parsed:
            continue
        
        periods = select_period_draws(code, cfg, parsed)
        
        for policy_label, draws in periods.items():
            print(f"\n  ▶ {policy_label}（{len(draws)}期）")
            print(f"  {'-'*60}")
            
            period_errors = 0
            for draw in draws:
                errs = compare_draw(code, cfg, draw)
                total_checked += 1
                if errs:
                    period_errors += len(errs)
                    print(f"    ✖ {draw['issue']} ({draw['date']}): {len(errs)}个差异")
                    for e in errs:
                        print(f"      - {e}")
                # else:
                #     print(f"    ✓ {draw['issue']} ({draw['date']}): 一致")
            
            if period_errors == 0:
                print(f"    ✓ 全部{len(draws)}期解析一致，无差异")
            else:
                total_errors += period_errors
                print(f"    ✖ 共 {period_errors} 个差异")
    
    print(f"\n{'='*70}")
    print(f"  总结：共校验 {total_checked} 期，发现 {total_errors} 个差异")
    print(f"{'='*70}")

if __name__ == "__main__":
    main()