const { initTables } = require('../db/database');

console.log('正在初始化数据库...');
initTables();
console.log('数据库初始化完成！');
console.log('默认管理员: admin / admin123');