## Опписание
1. Перейдите в папку проекта cd /путь/к/вашему/проекту
2. Соберите проект mvn clean install
3. Запустите приложение (выберите команду для вашей ОС) (macOS / Linux: java -cp "target/classes:target/dependency/*" com.example.WeatherService) / (Windows: java -cp "target/classes;target/dependency/*" com.example.WeatherService)
## Запуск
1. Необходимо иметь установленный на компьютере JDK 17.
2. Необходимо запустить redis с комьпьютера на порту 
3. git clone <URL_ВАШЕГО_РЕПОЗИТОРИЯ>
4. cd /путь/к/вашему/проекту/files-server-app
5. mvn clean install
6. java -jar target/FilesExchangeApp-1.0-SNAPSHOT.jar
