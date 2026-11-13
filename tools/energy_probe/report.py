"""Summarize raw measurements; package and core domains stay separate."""
import argparse
import csv
import json
from pathlib import Path
import statistics
from analysis import energy_window, subtract_bracket, summarize
from reference import cpu_reference


def core_windows(raw):
    channels=[name for name in raw['samples'][0]['channels'] if name.upper().endswith('_CORE')]
    # The exposed core domains are not assumed to form an exact partition of PKG.
    return {name:energy_window(raw['samples'][0],raw['samples'][-1],name) for name in channels}


def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('directory',type=Path)
    parser.add_argument('--profile',type=Path,default=Path(__file__).with_name('pixel6_cpu_reference.json'))
    parser.add_argument('--documentation',type=Path)
    args=parser.parse_args();out=args.directory.resolve()
    blocks=json.loads((out/'blocks.json').read_text(encoding='utf-8'))
    profile=json.loads(args.profile.read_text(encoding='utf-8'))
    enriched=[]
    for block in blocks:
        r=dict(block)
        windows=[json.loads((out/(block[k]+'.json')).read_text(encoding='utf-8')) for k in ['before','work','after']]
        cores=[core_windows(w) for w in windows]
        r['individual_core_domain_incremental_j']={name:subtract_bracket(*(c[name] for c in cores))['incremental_energy_j']
            for name in cores[0] if all(name in c for c in cores)}
        r['reference_cpu_work']=cpu_reference(r['incremental_guest_cpu_ms'],profile)
        enriched.append(r)
    by={}
    for b in enriched: by.setdefault(b['case'],[]).append(b)
    summary={}
    for case,rows in by.items():
        summary[case]={'host_incremental_j':summarize([b['incremental_energy_j'] for b in rows]),
            'host_incremental_w':summarize([b['incremental_power_w'] for b in rows]),
            'guest_incremental_cpu_ms':summarize([b['incremental_guest_cpu_ms'] for b in rows]),
            'baseline_drift_sensitive_blocks':sum(b['baseline_drift_sensitive'] for b in rows)}
        if rows[0]['cycles']:
            summary[case]['host_incremental_j_per_cycle']=summarize([b['incremental_host_j_per_cycle'] for b in rows])
    contrasts={}
    none={r['rep']:r for r in enriched if r['case']=='opening-none'}
    for case in ['opening-fade','opening-thumbnail']:
        pairs=[]
        for r in enriched:
            if r['case']==case and r['rep'] in none:
                base=none[r['rep']]
                pairs.append({'rep':r['rep'],
                    'host_j_per_open_difference':r['incremental_host_j_per_cycle']-base['incremental_host_j_per_cycle'],
                    'cpu_ms_per_open_difference':r['incremental_guest_cpu_ms']/r['cycles']-base['incremental_guest_cpu_ms']/base['cycles'],
                    'either_baseline_drift_sensitive':r['baseline_drift_sensitive'] or base['baseline_drift_sensitive']})
        if pairs: contrasts[case]={'paired_values':pairs,
            'host_j_per_open_difference':summarize([p['host_j_per_open_difference'] for p in pairs]),
            'guest_cpu_ms_per_open_difference':summarize([p['cpu_ms_per_open_difference'] for p in pairs])}
    result={'scope':'host package energy differences; package and cores are not summed',
        'phone_total_energy_j':None,'reference_profile':profile,'blocks':enriched,'cases':summary,
        'opening_contrasts_against_none':contrasts,
        'notes':['Negative baseline-subtracted values are retained.',
            'Intervals describe repeatability in this run; they do not cover phone-model uncertainty.',
            'Individual core domains are not summed or subtracted from PKG to infer GPU energy.',
            'CPU reference conversion assumes time scale 1 unless calibration pairs are provided.']}
    (out/'summary.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8')
    with (out/'summary.csv').open('w',encoding='utf-8-sig',newline='') as f:
        writer=csv.writer(f);writer.writerow(['case','blocks','mean_host_delta_j','bootstrap_low_j','bootstrap_high_j','mean_guest_delta_cpu_ms','high_drift_blocks'])
        for case,r in summary.items():
            e=r['host_incremental_j'];writer.writerow([case,e['count'],e['mean'],*e['bootstrap_mean_95pct_interval'],r['guest_incremental_cpu_ms']['mean'],r['baseline_drift_sensitive_blocks']])
    # Plot only after data collection has finished.
    import matplotlib
    matplotlib.use('Agg')
    import matplotlib.pyplot as plt
    order=[x for x in ['menu-held','opening-none','opening-fade','opening-thumbnail','scroll-loop','navigation-loop'] if x in summary]
    means=[summary[x]['host_incremental_j']['mean'] for x in order]
    intervals=[summary[x]['host_incremental_j']['bootstrap_mean_95pct_interval'] for x in order]
    errors=[[max(0,m-lo) for m,(lo,hi) in zip(means,intervals)],[max(0,hi-m) for m,(lo,hi) in zip(means,intervals)]]
    fig,ax=plt.subplots(figsize=(10,5))
    ax.bar(order,means,yerr=errors,capsize=5,color=['#536692' if x.startswith('opening') else '#748c78' for x in order])
    ax.axhline(0,color='#777',lw=.8);ax.set_ylabel('Host package energy above bracketing idle (J)')
    ax.set_title('Measured blocks; 95% bootstrap intervals across repetitions')
    ax.tick_params(axis='x',labelrotation=20);fig.tight_layout()
    fig.savefig(out/'host-incremental-energy.png',dpi=160);plt.close(fig)
    if args.documentation:
        names={'menu-held':'菜单静止','opening-none':'无动画打开','opening-fade':'缩放淡入打开',
               'opening-thumbnail':'缩略图展开','scroll-loop':'上下滚动','navigation-loop':'照片/相册切换'}
        table=[]
        for case in order:
            row=summary[case];energy=row['host_incremental_j'];lo,hi=energy['bootstrap_mean_95pct_interval']
            cpu=row['guest_incremental_cpu_ms']['mean']
            table.append(f"| {names[case]} | {energy['count']} | {energy['mean']:.2f} | {lo:.2f} 至 {hi:.2f} | {cpu:.0f} | {row['baseline_drift_sensitive_blocks']} |")
        comparison=[]
        for case,r in contrasts.items():
            e=r['host_j_per_open_difference'];c=r['guest_cpu_ms_per_open_difference'];lo,hi=e['bootstrap_mean_95pct_interval']
            clo,chi=c['bootstrap_mean_95pct_interval']
            comparison.append(f"| {names[case]} − 无动画 | {e['mean']:.2f} | {lo:.2f} 至 {hi:.2f} | {c['mean']:.1f} | {clo:.1f} 至 {chi:.1f} |")
        equivalent=[]
        for case in order:
            rows=by[case];factor=1 if case=='menu-held' else 6
            cpu=statistics.mean(r['incremental_guest_cpu_ms']/factor for r in rows)
            costs=[cpu*s['active_mw']/1000 for s in profile['scenarios']]
            equivalent.append(f"| {names[case]} | {'18 秒静置' if factor==1 else '一次往返交互'} | "+' | '.join(f'{c:.1f}' for c in costs)+' |')
        restore=json.loads((out/'restore.json').read_text())
        metadata=json.loads((out/'metadata.json').read_text())
        drift_count=sum(b['baseline_drift_sensitive'] for b in blocks)
        max_drift=max(abs(b['baseline_drift_w']) for b in blocks)
        pairs=[p for r in contrasts.values() for p in r['paired_values']]
        pair_drift=sum(p['either_baseline_drift_sensitive'] for p in pairs)
        trends=[]
        for case,r in contrasts.items():
            values=[p['host_j_per_open_difference'] for p in r['paired_values']]
            sign='均为正' if all(v>0 for v in values) else '均为负' if all(v<0 for v in values) else '有正有负或零'
            trends.append(f"{names[case]}的电脑包域差值在 {len(values)} 组配对中{sign}")
        menu_cpu=summary.get('menu-held',{}).get('guest_incremental_cpu_ms')
        menu_observation=''
        if menu_cpu:
            menu_rows=by['menu-held']
            menu_frames=[json.loads((out/(b['work']+'.json')).read_text())['gfxinfo_frames'] for b in menu_rows]
            seconds=statistics.mean(b['work_host_seconds'] for b in menu_rows)
            menu_observation=f"菜单静止的 {len(menu_rows)} 组绘制帧计数为 {menu_frames}，应用 CPU 增量为 {menu_cpu['min']:.0f} 至 {menu_cpu['max']:.0f} 毫秒，均值 {menu_cpu['mean']:.0f} 毫秒/约 {seconds:.0f} 秒。前一轮跟踪中已发现 Popup 位置轮询，应优先处理菜单静止期间持续触发的帧回调。"
        verification_path=out/'verification.json'
        verification=json.loads(verification_path.read_text()) if verification_path.exists() else {}
        restore_note=''
        if verification.get('keys_changed_since_original_launch')==['last_synced_generation']:
            restore_note='首次采样与续跑之间，应用重启更新了内部 last_synced_generation，其余保存项一致。'
        if verification.get('settings_restored_identically_to_resume_backup'):
            restore_note+=f"最终设置与续跑前备份逐字节一致，打开效果为 {verification['restored_effect']}。"
        body=f'''# 模拟器能耗估算工具与实测

本次新增 tools/energy_probe，并在可见 GalleryAlbumSearchQA20261019 模拟器的隔离应用 com.pixel.gallery.codexclean 上执行。应用 APK SHA-256 为 {metadata['apk_sha256']}。本轮未修改应用动画实现。

## 测量方法与范围

电脑为 AMD Ryzen 7 7840H / Radeon 780M。使用系统现有 Windows EMI 读取 RAPL_Package0_PKG 累计能量，普通权限运行，无额外驱动安装。原始单位为 pWh，换算为焦耳使用 ΔJ=ΔpWh×3.6e-9，平均功率采用计数器自身的 100ns 时钟，并核对主机时钟。每 0.5 秒记录一次累计计数；能量使用两个端点的差值。

包域为唯一总量。八个核心域分别保留作诊断，不与包域相加，也不由包域减核心域推算 GPU。这里的焦耳描述电脑端计量域，手机端模型另列。

每个测试组包含前置静置、工作窗口、后置静置；工作窗口通常 18 秒，交互测试执行 6 次往返，菜单测试保持菜单静止。实际时长均按计量时钟保存。基线功率按工作中点在前后两段静置之间插值，扣除等时长基线；负值保留。前后基线功率差超过 max(1 W, 基线的 10%) 时标记漂移敏感，所有组均保留在汇总中。三种打开效果使用平衡顺序，缓存预热和界面校验位于计量窗口之外。

## 实测结果

完成 {len(blocks)} 个工作组，原始事件、计数、时间戳及两侧基线均已保存。表中能量是每个工作窗口扣除静置基线后的差值；区间为重复组均值的 95% 百分位 bootstrap 区间，小样本结果用于探索比较。

| 交互 | 工作组数 | 电脑包域增量能量均值 J | 重复测量区间 J | 应用 CPU 增量均值 ms | 漂移敏感组数 |
| --- | --- | --- | --- | --- | --- |
{chr(10).join(table)}

各组工作时间相近，但菜单一组为持续静置，其他一组为六次往返交互。打开效果每次均打开同一张自有 portrait.jpg，再返回主页；设置值通过实际 Preferences protobuf 解码核对，预热期间通过文件详情确认目标图片。

| 同一轮中与无动画配对 | 电脑包域差值 J/次往返 | 重复测量区间 J/次 | 应用 CPU 差值 ms/次 | CPU 重复测量区间 ms/次 |
| --- | --- | --- | --- | --- |
{chr(10).join(comparison)}

{'；'.join(trends)}。{len(blocks)} 组中有 {drift_count} 组基线明显漂移，最大为 {max_drift:.2f} W；{len(pairs)} 组打开效果配对中有 {pair_drift} 组包含漂移敏感窗口。能量差与漂移同时存在，本次未得到可稳定用于选择三种效果的能耗排序。区间仅描述本次重复测量；主机至手机的换算误差另需配对校准。所有原始值均保留。

{menu_observation}

## 手机 CPU 参考折算

参考配置为 Pixel 6 / Tensor G1，采用 Perfetto v59.1 公开 Wattson 曲线中的三个 CPU 活跃功率点：小核 1.098 GHz 为 115.83 mW（依赖中核 1.197 GHz），中核 1.197 GHz 为 132.46 mW，大核 1.426 GHz 为 382.61 mW。它们是三种独立条件，没有相加。

下表把应用 CPU 增量时间按时间换算系数 k=1 做工作量折算，单位 mJ。k=1 是条件设定，当前未经过模拟器/手机配对测量校准。GPU、显示、共享簇静态功率、空闲状态及其他组件未包含；phone_total_energy_j 保持空值。

| 交互 | 单位 | 小核参考 mJ | 中核参考 mJ | 大核参考 mJ |
| --- | --- | --- | --- | --- |
{chr(10).join(equivalent)}

reference.py 只在提供至少三组附来源的正值 CPU 时间配对数据后，计算参考手机/模拟器 CPU 时间的中位换算系数。当前未提供该校准；完整手机能耗与手机电池百分比未生成。

## 验证与恢复

采样换算、时钟异常、计数器重置、非对称基线、负差值保留、重复区间、二进制设置解析及缺失校准处理均有独立检查。受控单线程计算探针已验证计数器随负载响应，原始记录见 energy-research/controlled-load-probe/probe.json。

一次设置校验器把 SCALE_FADE 的二进制长度字节当作文本换行，造成采样中断。已改用 protobuf 字段解析并增加回归检查，前三个完成组保留，续跑跳过已有组；中断日志保留。初期另有一组菜单预采样出现明显基线漂移，单独保留于 menu-pilot，不混入本表。

每次采样会保存当时设置，在应用停止后恢复，并核对原始字节。本次最后恢复核对为 {restore['identical']}，SHA-256 为 {restore['sha256']}。{restore_note}模拟器保持可见，测试未执行媒体移动、复制或删除。

## 产物与复现

- 工具及命令说明：tools/energy_probe/README.md。
- 原始记录目录：{out.relative_to(Path(__file__).resolve().parents[2]).as_posix()}。
- summary.json 保留组明细、对比和条件参考值，summary.csv 供表格读取。
- host-incremental-energy.png 展示电脑包域工作组差值及重复区间。

依据 [Microsoft EMI](https://learn.microsoft.com/windows-hardware/drivers/powermeter/energy-meter-interface)、[EMI 计量单位](https://learn.microsoft.com/windows/win32/api/emi/ns-emi-emi_channel_measurement_data)、[WattsOn](https://www.microsoft.com/en-us/research/wp-content/uploads/2012/08/WattsonMobicom12.pdf)、[GreenScaler](https://softwareprocess.es/pubs/chowdhury2018EMSE-GreenScaler.pdf) 与 [Android Wattson](https://source.android.com/docs/core/power/wattson/wattson)。
'''
        args.documentation.parent.mkdir(parents=True,exist_ok=True)
        args.documentation.write_text(body,encoding='utf-8')
    print(json.dumps({'cases':{k:{'blocks':v['host_incremental_j']['count'],'mean_host_delta_j':round(v['host_incremental_j']['mean'],3),'guest_delta_cpu_ms':round(v['guest_incremental_cpu_ms']['mean'],2),'high_drift':v['baseline_drift_sensitive_blocks']} for k,v in summary.items()},'opening_contrasts':{k:v['host_j_per_open_difference'] for k,v in contrasts.items()}},indent=2))


if __name__=='__main__':main()
