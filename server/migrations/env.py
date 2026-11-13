from alembic import context
from sqlalchemy import engine_from_config, pool
from momo.config import settings
from momo.db import Base
# Retired companion tables are retained to preserve existing data. Never generate a drop.
def include_name(name, type_, parent_names):
    return not (type_ == "table" and name in {"phone_pairs", "phone_devices", "phone_episodes", "phone_jobs"})

config = context.config
config.set_main_option("sqlalchemy.url", settings.database_url.replace("%", "%%"))
target_metadata = Base.metadata
if context.is_offline_mode():
    context.configure(url=settings.database_url, target_metadata=target_metadata, include_name=include_name, literal_binds=True,
                      dialect_opts={"paramstyle": "named"})
    with context.begin_transaction():
        context.run_migrations()
else:
    engine = engine_from_config(config.get_section(config.config_ini_section), prefix="sqlalchemy.", poolclass=pool.NullPool)
    with engine.connect() as connection:
        context.configure(connection=connection, target_metadata=target_metadata, include_name=include_name)
        with context.begin_transaction():
            context.run_migrations()
