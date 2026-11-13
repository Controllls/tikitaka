"""Opt-in phone pairing, scoped devices and completed-video copies."""
from alembic import op
import sqlalchemy as sa

revision="0004_phone_companion"
down_revision="0003_highlight_artifacts"
branch_labels=None
depends_on=None

def upgrade():
    op.create_table('phone_pairs',sa.Column('id',sa.String(36),primary_key=True),sa.Column('owner',sa.String(36),sa.ForeignKey('users.id'),nullable=False),sa.Column('code_hash',sa.String(64),unique=True,nullable=False),sa.Column('expires',sa.Float(),nullable=False),sa.Column('used',sa.Boolean(),nullable=False))
    op.create_index('ix_phone_pairs_owner','phone_pairs',['owner'])
    op.create_table('phone_devices',sa.Column('id',sa.String(36),primary_key=True),sa.Column('owner',sa.String(36),sa.ForeignKey('users.id'),nullable=False),sa.Column('token_hash',sa.String(64),unique=True,nullable=False),sa.Column('name',sa.String(80),nullable=False),sa.Column('approved',sa.Boolean(),nullable=False),sa.Column('revoked',sa.Boolean(),nullable=False),sa.Column('expires',sa.Float(),nullable=False))
    op.create_index('ix_phone_devices_owner','phone_devices',['owner'])
    op.create_table('phone_episodes',sa.Column('id',sa.String(64),primary_key=True),sa.Column('owner',sa.String(36),sa.ForeignKey('users.id'),nullable=False),sa.Column('metadata_json',sa.JSON(),nullable=False),sa.Column('deleted',sa.Boolean(),nullable=False))
    op.create_index('ix_phone_episodes_owner','phone_episodes',['owner'])
    op.create_table('phone_jobs',sa.Column('owner',sa.String(36),sa.ForeignKey('users.id'),primary_key=True),sa.Column('jobs',sa.JSON(),nullable=False))

def downgrade():
    for name in ('phone_jobs','phone_episodes','phone_devices','phone_pairs'):op.drop_table(name)
