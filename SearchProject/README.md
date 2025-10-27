# Simple Java Web Search Engine  
**MIT License**

---

## 0xh7 Engine

A lightweight **Java-based web crawler and search engine** built from scratch.  
It crawls websites, downloads pages, indexes text, and performs keyword search using a simple **TF-IDF** scoring model.

---

**Made by 0xh7**

> ⚠️ No respect for `robots.txt`



## How to run the project

1. Open Command Prompt and go to the project 
```cmd
cd C:\CJavaProjects\SearchProject 




2. Compile all source files into the out folder

javac -d out src\util\*.java src\crawler\*.java src\indexer\*.java src\search\*.java src\app\*.java


3. General form
java -cp out app.Main <Url> <depth> <mode> <query>

~~mode (s)Single thread (m) Multi thread~~









## Examples
Multi thread  with depth 2
java -cp out app.Main https://example.com 2 m example


single-thread with depth 3
java -cp out app.Main https://example.com 3 s example


