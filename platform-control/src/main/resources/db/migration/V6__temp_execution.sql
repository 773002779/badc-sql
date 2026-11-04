-- 平台元数据库：运维临时执行（V6，运维临时执行）
--
-- 为什么给 ticket_type 加 TEMP_EXEC：
--   运维临时执行通道里，L3 变更不能当场直执行，要转成一张工单，且**只由组长一级审批**
--   （不经二级审批）。这张单是真的变更单：有提交人、有风险等级、有执行记录、有审计，
--   只是审批链短一段。把类型落到 ticket_type 上，后续按类型分流审批链与查询只需判断这一列，
--   不必再加一个"是否临时执行"的标记列（两列表达同一件事，迟早互相矛盾）。
--
-- 为什么不新建一张表而是复用 ticket：
--   漂移检测、执行前快照、风险确认、执行锁、审计这五条闸已经全部挂在工单上
--   （schema_snapshot / risk_acknowledgement / datasource_exec_lock / ticket_event / audit_event）。
--   另起一张表等于把这五条闸再实现一遍，两套实现会随迭代分叉——同一个数据源上
--   "工单挡住了、临时执行没挡住"这类缺口迟早出现。复用 ticket 也让 TEMP_EXEC 单
--   天然带着同一套执行与回滚路径，不需要为它单独再写一遍执行器调用。
--
-- 为什么删掉旧 CHECK 再按新定义重建，且必须合成一条语句：
--   MySQL 不支持直接修改已有 CHECK 的内容（ALTER TABLE ... ALTER CHECK 只能启停约束），
--   只能删掉约束、再按新定义重建。约束名沿用 V3 的 ck_ticket_ticket_type。
--   重建后的定义是旧定义的超集，只多出 TEMP_EXEC —— 库里已存在的 NORMAL/ROLLBACK 行
--   不会因为本迁移被拒。
--   删除与重建**写在同一条 ALTER TABLE 里**，不是风格问题：MySQL 的 DDL 不在事务中，
--   拆成两条时若第一条成功、第二条失败（或进程在两条之间中断），库里就留下"约束已删、
--   迁移记录未写"的半截状态；重跑迁移时第一条会在 DROP CHECK 处报"约束不存在"，Flyway
--   就此卡住，只能人工介入。合成一条就是一个原子 DDL，失败则什么都没变，重跑安全。
--   V6 是**新增**迁移，不是对 V3 的改写：已迁移过的库正常升级即可，**不需要** flyway repair
--   （repair 只在改了已应用的迁移文件、校验和失配时才需要）。
--
-- 约定沿用 V1–V5：枚举列加 CHECK。CHECK 自 MySQL 8.0.16 起才真正生效，
--   更低版本的服务器会**静默忽略**它 —— 平台元数据库最低要求 MySQL 8.0.16。

ALTER TABLE ticket
    DROP CHECK ck_ticket_ticket_type,
    ADD CONSTRAINT ck_ticket_ticket_type CHECK (ticket_type IN ('NORMAL', 'ROLLBACK', 'TEMP_EXEC'));
