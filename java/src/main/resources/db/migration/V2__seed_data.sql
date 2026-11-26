-- Sample data so both endpoints are useful immediately. Interest after one balance update, per deposit:
-- 1 basic   1200.00 day  45 -> +1.00 (1200 * 1% / 12)
-- 2 student  400.00 day 100 -> +1.00 (400 * 3% / 12)
-- 3 premium  240.00 day  60 -> +1.00 (240 * 5% / 12)
-- 4 basic   1000.00 day  20 -> none (first 30 days)
-- 5 student  400.00 day 400 -> none (student interest stops after one year)
-- 6 premium  240.00 day  40 -> none (premium interest starts after 45 days)

insert into time_deposits (id, plan_type, days, balance)
values (1, 'basic', 45, 1200.00),
       (2, 'student', 100, 400.00),
       (3, 'premium', 60, 240.00),
       (4, 'basic', 20, 1000.00),
       (5, 'student', 400, 400.00),
       (6, 'premium', 40, 240.00);

insert into withdrawals (id, time_deposit_id, amount, date)
values (1, 2, 50.00, '2026-01-15'),
       (2, 3, 100.00, '2026-02-01'),
       (3, 3, 25.50, '2026-03-10');

select setval(pg_get_serial_sequence('time_deposits', 'id'), (select max(id) from time_deposits));
select setval(pg_get_serial_sequence('withdrawals', 'id'), (select max(id) from withdrawals));
