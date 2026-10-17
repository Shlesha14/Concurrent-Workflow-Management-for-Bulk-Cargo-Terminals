USE portflow;
INSERT INTO resource_type VALUES
 ('CRANE','Quay/yard crane','SLOTS'),('CONVEYOR','Conveyor line','SLOTS'),('FORKLIFT','Forklift','SLOTS'),
 ('TRUCK','Transport vehicle','SLOTS'),('INSPECTION_STATION','Inspection station','SLOTS'),('RECLAIMER','Stockyard reclaimer','SLOTS'),
 ('COLD_ROOM','Cool room for fresh produce','QUANTITY'),('CHILLED_STORE','Chilled store','QUANTITY'),
 ('FROZEN_STORE','Frozen store','QUANTITY'),('DRY_STORE','Dry / protected store','QUANTITY'),('STOCKYARD','Bulk stockyard','QUANTITY');

INSERT INTO handling_profile VALUES
 ('LEAFY_VEG','Leafy vegetables','FRESH_PRODUCE',TRUE,0.0,4.0,90,'REJECT','Cool-room storage 0-4 C, short shelf life','Real produce needs vary by variety and ripeness'),
 ('TOMATO','Tomatoes','FRESH_PRODUCE',TRUE,10.0,14.0,120,'REJECT','Tomatoes keep best at 10-14 C (not near freezing)','Ripeness stage ignored'),
 ('APPLE','Apples','FRESH_PRODUCE',TRUE,0.0,4.0,240,'REJECT','Cool storage 0-4 C','Variety differences ignored'),
 ('BANANA','Bananas','FRESH_PRODUCE',TRUE,13.0,15.0,120,'REJECT','Bananas are chilling-sensitive: 13-15 C','Ripening rooms not modelled'),
 ('FROZEN_FOOD','Frozen food','FROZEN_CHILLED',TRUE,-25.0,-18.0,30,'QUARANTINE','Ice cream, frozen meat: -25..-18 C','Cold-chain regulations simplified'),
 ('CHILLED_FOOD','Chilled dairy / meat','FROZEN_CHILLED',TRUE,0.0,5.0,45,'QUARANTINE','Chilled products 0-5 C','Cold-chain regulations simplified'),
 ('ELECTRONICS','Electronics / fragile','ELECTRONICS',FALSE,NULL,NULL,480,'REJECT','Fragile handling, inspection, protected storage','Insurance and customs ignored'),
 ('COAL','Coal / dry bulk','DRY_BULK',FALSE,NULL,NULL,720,'REJECT','Bulk unloading, dust-suppressed conveyor, stockyard, reclaimer','Environmental permits ignored'),
 ('CEMENT_BAGGED','Cement (bagged)','CONSTRUCTION',FALSE,NULL,NULL,360,'REJECT','Moisture-proof dry store','Shelf life optional'),
 ('CEMENT_BULK','Cement (bulk)','CONSTRUCTION',FALSE,NULL,NULL,360,'REJECT','Bulk transfer to moisture-proof silo store','Silo aeration ignored'),
 ('GENERAL','General cargo','GENERAL',FALSE,NULL,NULL,480,'REJECT','Default profile for packaged goods and machinery','Generic handling only');

-- Produce profiles share one workflow shape: UNLOAD || PREP_STORAGE -> INSPECT -> STORE -> DISPATCH
INSERT INTO profile_step (profile_code, step_code, task_type, base_min, per_unit_min)
SELECT p.profile_code, s.step_code, s.task_type, s.base_min, s.per_unit_min FROM handling_profile p JOIN (
  SELECT 'UNLOAD' step_code,'UNLOAD' task_type,10 base_min,0.02 per_unit_min UNION ALL
  SELECT 'PREP_STORAGE','PREPARE',5,0 UNION ALL SELECT 'INSPECT','INSPECT',8,0 UNION ALL
  SELECT 'STORE','STORE',6,0.01 UNION ALL SELECT 'DISPATCH','DISPATCH',8,0) s
WHERE p.category='FRESH_PRODUCE';
INSERT INTO profile_step_dep SELECT p.profile_code, d.s, d.d FROM handling_profile p JOIN (
  SELECT 'INSPECT' s,'UNLOAD' d UNION ALL SELECT 'STORE','INSPECT' UNION ALL SELECT 'STORE','PREP_STORAGE' UNION ALL SELECT 'DISPATCH','STORE') d
WHERE p.category='FRESH_PRODUCE';
INSERT INTO profile_step_req (profile_code, step_code, resource_type, capability, amount_mode, hold_until_step)
SELECT p.profile_code, r.step_code, r.rt, NULL, r.mode, r.hold FROM handling_profile p JOIN (
  SELECT 'UNLOAD' step_code,'CRANE' rt,'SLOT' mode,NULL hold UNION ALL SELECT 'PREP_STORAGE','FORKLIFT','SLOT',NULL
  UNION ALL SELECT 'INSPECT','INSPECTION_STATION','SLOT',NULL UNION ALL SELECT 'STORE','COLD_ROOM','CARGO_QTY','DISPATCH'
  UNION ALL SELECT 'STORE','FORKLIFT','SLOT',NULL UNION ALL SELECT 'DISPATCH','TRUCK','SLOT',NULL) r
WHERE p.category='FRESH_PRODUCE';

-- Frozen / chilled: UNLOAD -> [INSPECT] -> STORE -> MONITOR_STORAGE -> DISPATCH
INSERT INTO profile_step VALUES
 ('FROZEN_FOOD','UNLOAD','UNLOAD',8,0.01),('FROZEN_FOOD','STORE','STORE',6,0.01),('FROZEN_FOOD','MONITOR_STORAGE','MONITOR',10,0),('FROZEN_FOOD','DISPATCH','DISPATCH',8,0),
 ('CHILLED_FOOD','UNLOAD','UNLOAD',8,0.01),('CHILLED_FOOD','INSPECT','INSPECT',8,0),('CHILLED_FOOD','STORE','STORE',6,0.01),('CHILLED_FOOD','MONITOR_STORAGE','MONITOR',10,0),('CHILLED_FOOD','DISPATCH','DISPATCH',8,0);
INSERT INTO profile_step_dep VALUES
 ('FROZEN_FOOD','STORE','UNLOAD'),('FROZEN_FOOD','MONITOR_STORAGE','STORE'),('FROZEN_FOOD','DISPATCH','MONITOR_STORAGE'),
 ('CHILLED_FOOD','INSPECT','UNLOAD'),('CHILLED_FOOD','STORE','INSPECT'),('CHILLED_FOOD','MONITOR_STORAGE','STORE'),('CHILLED_FOOD','DISPATCH','MONITOR_STORAGE');
INSERT INTO profile_step_req (profile_code, step_code, resource_type, capability, amount_mode, hold_until_step) VALUES
 ('FROZEN_FOOD','UNLOAD','CRANE',NULL,'SLOT',NULL),('FROZEN_FOOD','STORE','FROZEN_STORE',NULL,'CARGO_QTY','DISPATCH'),('FROZEN_FOOD','STORE','FORKLIFT',NULL,'SLOT',NULL),('FROZEN_FOOD','DISPATCH','TRUCK',NULL,'SLOT',NULL),
 ('CHILLED_FOOD','UNLOAD','CRANE',NULL,'SLOT',NULL),('CHILLED_FOOD','INSPECT','INSPECTION_STATION',NULL,'SLOT',NULL),('CHILLED_FOOD','STORE','CHILLED_STORE',NULL,'CARGO_QTY','DISPATCH'),('CHILLED_FOOD','STORE','FORKLIFT',NULL,'SLOT',NULL),('CHILLED_FOOD','DISPATCH','TRUCK',NULL,'SLOT',NULL);

-- Electronics: UNLOAD -> INSPECT -> STORE -> DISPATCH (fragile-capable equipment, protected store)
INSERT INTO profile_step VALUES ('ELECTRONICS','UNLOAD','UNLOAD',12,0.02),('ELECTRONICS','INSPECT','INSPECT',15,0.01),('ELECTRONICS','STORE','STORE',8,0.01),('ELECTRONICS','DISPATCH','DISPATCH',10,0);
INSERT INTO profile_step_dep VALUES ('ELECTRONICS','INSPECT','UNLOAD'),('ELECTRONICS','STORE','INSPECT'),('ELECTRONICS','DISPATCH','STORE');
INSERT INTO profile_step_req (profile_code, step_code, resource_type, capability, amount_mode, hold_until_step) VALUES
 ('ELECTRONICS','UNLOAD','CRANE','FRAGILE_HANDLING','SLOT',NULL),('ELECTRONICS','INSPECT','INSPECTION_STATION',NULL,'SLOT',NULL),
 ('ELECTRONICS','STORE','DRY_STORE','FRAGILE_SAFE','CARGO_QTY','DISPATCH'),('ELECTRONICS','STORE','FORKLIFT','FRAGILE_HANDLING','SLOT',NULL),('ELECTRONICS','DISPATCH','TRUCK',NULL,'SLOT',NULL);

-- Coal: UNLOAD -> TRANSFER -> STACK -> RECLAIM
INSERT INTO profile_step VALUES ('COAL','UNLOAD','UNLOAD',20,0.01),('COAL','TRANSFER','TRANSFER',15,0.005),('COAL','STACK','STORE',10,0.005),('COAL','RECLAIM','RECLAIM',20,0.005);
INSERT INTO profile_step_dep VALUES ('COAL','TRANSFER','UNLOAD'),('COAL','STACK','TRANSFER'),('COAL','RECLAIM','STACK');
INSERT INTO profile_step_req (profile_code, step_code, resource_type, capability, amount_mode, hold_until_step) VALUES
 ('COAL','UNLOAD','CRANE','BULK_GRAB','SLOT',NULL),('COAL','TRANSFER','CONVEYOR','DUST_SUPPRESSED','SLOT',NULL),
 ('COAL','STACK','STOCKYARD',NULL,'CARGO_QTY','RECLAIM'),('COAL','RECLAIM','RECLAIMER',NULL,'SLOT',NULL);

-- Cement bagged: UNLOAD -> STORE -> DISPATCH ; bulk: UNLOAD -> TRANSFER -> STORE -> DISPATCH
INSERT INTO profile_step VALUES ('CEMENT_BAGGED','UNLOAD','UNLOAD',10,0.01),('CEMENT_BAGGED','STORE','STORE',8,0.01),('CEMENT_BAGGED','DISPATCH','DISPATCH',8,0),
 ('CEMENT_BULK','UNLOAD','UNLOAD',12,0.01),('CEMENT_BULK','TRANSFER','TRANSFER',12,0.005),('CEMENT_BULK','STORE','STORE',8,0.005),('CEMENT_BULK','DISPATCH','DISPATCH',8,0);
INSERT INTO profile_step_dep VALUES ('CEMENT_BAGGED','STORE','UNLOAD'),('CEMENT_BAGGED','DISPATCH','STORE'),
 ('CEMENT_BULK','TRANSFER','UNLOAD'),('CEMENT_BULK','STORE','TRANSFER'),('CEMENT_BULK','DISPATCH','STORE');
INSERT INTO profile_step_req (profile_code, step_code, resource_type, capability, amount_mode, hold_until_step) VALUES
 ('CEMENT_BAGGED','UNLOAD','CRANE',NULL,'SLOT',NULL),('CEMENT_BAGGED','STORE','DRY_STORE','MOISTURE_PROOF','CARGO_QTY','DISPATCH'),('CEMENT_BAGGED','STORE','FORKLIFT',NULL,'SLOT',NULL),('CEMENT_BAGGED','DISPATCH','TRUCK',NULL,'SLOT',NULL),
 ('CEMENT_BULK','UNLOAD','CRANE',NULL,'SLOT',NULL),('CEMENT_BULK','TRANSFER','CONVEYOR',NULL,'SLOT',NULL),('CEMENT_BULK','STORE','DRY_STORE','MOISTURE_PROOF','CARGO_QTY','DISPATCH'),('CEMENT_BULK','DISPATCH','TRUCK',NULL,'SLOT',NULL);

-- General: UNLOAD -> STORE -> DISPATCH
INSERT INTO profile_step VALUES ('GENERAL','UNLOAD','UNLOAD',10,0.01),('GENERAL','STORE','STORE',8,0.01),('GENERAL','DISPATCH','DISPATCH',8,0);
INSERT INTO profile_step_dep VALUES ('GENERAL','STORE','UNLOAD'),('GENERAL','DISPATCH','STORE');
INSERT INTO profile_step_req (profile_code, step_code, resource_type, capability, amount_mode, hold_until_step) VALUES
 ('GENERAL','UNLOAD','CRANE',NULL,'SLOT',NULL),('GENERAL','STORE','DRY_STORE',NULL,'CARGO_QTY','DISPATCH'),('GENERAL','STORE','FORKLIFT',NULL,'SLOT',NULL),('GENERAL','DISPATCH','TRUCK',NULL,'SLOT',NULL);

-- Demo resources (notebook: Crane CR1 capacity 2 at Terminal B; storage 1000 = 300 + 700)
INSERT INTO resource (name,type_code,capacity,status,location,temp_min_c,temp_max_c) VALUES
 ('CR1','CRANE',2,'AVAILABLE','Terminal B',NULL,NULL),('CR2','CRANE',1,'AVAILABLE','Terminal B',NULL,NULL),
 ('CONV1','CONVEYOR',1,'AVAILABLE','Terminal B',NULL,NULL),('CONV2','CONVEYOR',1,'AVAILABLE','Terminal B',NULL,NULL),
 ('FORK1','FORKLIFT',1,'AVAILABLE','Yard',NULL,NULL),('FORK2','FORKLIFT',1,'AVAILABLE','Yard',NULL,NULL),
 ('TRUCK1','TRUCK',1,'AVAILABLE','Gate',NULL,NULL),('TRUCK2','TRUCK',1,'AVAILABLE','Gate',NULL,NULL),
 ('INSP1','INSPECTION_STATION',2,'AVAILABLE','Shed 1',NULL,NULL),('RECL1','RECLAIMER',1,'AVAILABLE','Stockyard',NULL,NULL),
 ('COLD-A','COLD_ROOM',300,'AVAILABLE','Cold block',1.0,3.0),('COLD-B','COLD_ROOM',200,'AVAILABLE','Cold block',11.0,13.0),
 ('COLD-C','COLD_ROOM',150,'AVAILABLE','Cold block',13.5,14.5),('CHILL1','CHILLED_STORE',250,'AVAILABLE','Cold block',1.0,4.0),
 ('FRZ1','FROZEN_STORE',400,'AVAILABLE','Cold block',-24.0,-20.0),
 ('DRY-A','DRY_STORE',300,'AVAILABLE','Warehouse',NULL,NULL),('DRY-B','DRY_STORE',700,'AVAILABLE','Warehouse',NULL,NULL),
 ('STOCK1','STOCKYARD',5000,'AVAILABLE','Stockyard',NULL,NULL),('STOCK2','STOCKYARD',3000,'AVAILABLE','Stockyard',NULL,NULL);
INSERT INTO resource_capability (resource_id, capability)
SELECT resource_id, c FROM resource JOIN (
 SELECT 'CR1' n,'BULK_GRAB' c UNION ALL SELECT 'CR2','FRAGILE_HANDLING' UNION ALL SELECT 'CONV1','DUST_SUPPRESSED'
 UNION ALL SELECT 'FORK2','FRAGILE_HANDLING' UNION ALL SELECT 'DRY-A','FRAGILE_SAFE' UNION ALL SELECT 'DRY-A','MOISTURE_PROOF'
 UNION ALL SELECT 'DRY-B','MOISTURE_PROOF') x ON x.n = resource.name;

INSERT INTO app_setting VALUES
 ('engine.workers','4'),('engine.poll_ms','100'),('engine.max_retries','2'),('engine.backoff_ms','200'),('engine.lock_timeout_ms','3000'),
 ('sim.speed','60'),('sim.excursion_probability','0.0'),
 ('weight.priority','0.30'),('weight.urgency','0.40'),('weight.wait','0.20'),('weight.short','0.10'),('urgency.horizon_min','480'),('ui.refresh_ms','1000');
