-- 선정 품질 데이터셋 추출 (2026-10-01). 진입분+대조군 전체에 대해 진입일 종가 → D+5/10/15 종가 수익과
-- 같은 날 유니버스 동일가중(1,000원·거래대금 5억 이상) 수익을 붙인다. 청산 규칙과 무관한 "고른 종목이 시장보다 나았나".
-- 실행(VM): sudo docker cp export.sql sa-postgres:/tmp/ && sudo docker exec sa-postgres psql -U stockadvisor -d stockadvisor -q -f /tmp/export.sql
--          sudo docker cp sa-postgres:/tmp/ds.csv /tmp/ds.csv   (약 35초)
create temp table r as select stock_code, business_date, close_price, volume,
  row_number() over (partition by stock_code order by business_date) rn
  from daily_price where business_date >= '20260601';
create index on r(stock_code, business_date); create index on r(stock_code, rn);
create temp table u as
 select b.business_date d, f.rn-b.rn k, avg((f.close_price-b.close_price)*100.0/b.close_price) ret, count(*) n
 from r b join r f on f.stock_code=b.stock_code and f.rn-b.rn in (1,3,5,10,15)
 where b.business_date>='20260620' and b.close_price>=1000 and b.close_price*b.volume>=500000000
 group by 1,2;
\copy (select o.id, o.strategy, o.control_sample c, coalesce(o.reject_reason,'') rr, o.alert_date, o.entry_market mk, o.entry_market_trend tr, o.buy_price, b.close_price c0, o.entry_change_rate chg, o.entry_volume_ratio vr, o.entry_market_cap cap, o.entry_rec_score rec, o.entry_ret5d_pct r5, o.entry_dist_high_pct dh, o.entry_atr_pct atr, o.entry_exec_strength ex, o.entry_news_cnt_1h nc, o.entry_frgn_ntby_ratio fr, o.entry_orgn_ntby_ratio og, o.entry_obi5 obi, o.entry_gap_pct gap, o.entry_market_breadth_pct br, o.entry_index_mom30 m30, o.entry_per per, o.entry_pbr pbr, to_char(o.alert_time at time zone 'Asia/Seoul','HH24MI') hm, (select close_price from r f where f.stock_code=o.stock_code and f.rn=b.rn+5) c5, (select close_price from r f where f.stock_code=o.stock_code and f.rn=b.rn+10) c10, (select close_price from r f where f.stock_code=o.stock_code and f.rn=b.rn+15) c15, (select ret from u where u.d=o.alert_date and u.k=5) u5, (select ret from u where u.d=o.alert_date and u.k=10) u10, (select ret from u where u.d=o.alert_date and u.k=15) u15 from trade_outcome o join r b on b.stock_code=o.stock_code and b.business_date=o.alert_date where o.stock_code not in ('114800','251340')) to '/tmp/ds.csv' csv header
