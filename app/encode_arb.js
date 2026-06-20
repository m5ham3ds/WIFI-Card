const fs = require('fs');
const path = require('path');

function encodeArabic(str) {
    return str.replace(/[\u0600-\u06FF]/g, function(c) {
        return '\\u' + ('0000' + c.charCodeAt(0).toString(16)).slice(-4);
    });
}

function processDirectory(dir) {
    const files = fs.readdirSync(dir);
    for (const file of files) {
        const fullPath = path.join(dir, file);
        const stat = fs.statSync(fullPath);
        if (stat.isDirectory()) {
            processDirectory(fullPath);
        } else if (fullPath.endsWith('.kt')) {
            const content = fs.readFileSync(fullPath, 'utf8');
            const encodedContent = encodeArabic(content);
            if (content !== encodedContent) {
                fs.writeFileSync(fullPath, encodedContent, 'utf8');
                console.log('Encoded: ' + fullPath);
            }
        }
    }
}

processDirectory('./app/src/main/java/');
